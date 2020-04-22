package magenta.tasks.gcp

import com.google.api.services.deploymentmanager.DeploymentManager
import com.google.api.services.deploymentmanager.model.Deployment
import magenta.{DeployReporter, KeyRing}
import magenta.tasks.{PollingCheck, Task}
import magenta.tasks.gcp.Gcp.DeploymentManagerApi._
import magenta.tasks.gcp.GcpRetryHelper.Result

import scala.concurrent.duration.FiniteDuration

object DeploymentManagerTasks {
  def updateTask(project: String, deploymentName: String, bundle: DeploymentBundle, maxWait: FiniteDuration, upsert: Boolean, preview: Boolean)(implicit kr: KeyRing): Task = new Task with PollingCheck {

    override def name: String = "DeploymentManagerUpdate"

    override def keyRing: KeyRing = kr

    val operationDescription: String = {
      val u = if (upsert) "Upsert" else "Update"
      if (preview) s"Preview ${u.toLowerCase}" else u
    }

    override def description: String = s"$operationDescription deployment manager deployment '$deploymentName' in GCP project '$project' using ${bundle.configPath} and $dependencyDesc"
    def dependencyDesc: String = {
      bundle.deps.keys.toList match {
        case Nil => "no dependencies"
        case singleton :: Nil => s"dependency $singleton"
        case multiple => s"dependencies ${multiple.mkString(", ")}"
      }
    }

    override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
      val credentials = Gcp.credentials.getCredentials(keyRing).getOrElse(reporter.fail("Unable to build GCP credentials from keyring"))
      val client = Gcp.DeploymentManagerApi.client(credentials)

      val result = for {
        maybeDeployment <- Gcp.DeploymentManagerApi.get(client, project, deploymentName)(reporter)
        operation <- runOperation(client, maybeDeployment)(reporter)
      } yield pollOperation(client, operation)(reporter, stopFlag)

      result.left.foreach(error => reporter.fail("DeployManager update operation failed", error))
    }

    def runOperation(client: DeploymentManager, maybeDeployment: Option[Deployment])(reporter: DeployReporter): Result[DMOperation] = {
      maybeDeployment.fold{
        if (upsert) {
          reporter.verbose(s"Deployment ${deploymentName} doesn't exist; inserting new deployment")
          Gcp.DeploymentManagerApi.insert(client, project, deploymentName, bundle, preview)(reporter)
        } else {
          reporter.fail(s"Deployment ${deploymentName} doesn't exist and upserting isn't enabled.")
        }
      }{ deployment =>
        reporter.verbose(s"Fetched details for deployment ${deploymentName}; using fingerprint ${deployment.getFingerprint} for update")
        Gcp.DeploymentManagerApi.update(client, project, deploymentName, deployment.getFingerprint, bundle, preview)(reporter)
      }
    }

    def pollOperation(client: DeploymentManager, operation: DMOperation)(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
      check(reporter, stopFlag) {
        val maybeUpdatedOperation: Result[DMOperation] = Gcp.DeploymentManagerApi.operationStatus(client, operation)(reporter)
        maybeUpdatedOperation.fold(
          error => reporter.fail("DeployManager operation status failed", error),
          {
            case Success(_, _) => true // exit check
            case Failure(_, _, errors) =>
              errors.foreach { error =>
                reporter.verbose(s"Operation error: $error")
              }
              reporter.fail("Failed to successfully execute update, errors logged verbosely")
            case InProgress(_, _, progress) =>
              reporter.verbose(s"Operation progress $progress%")
              false // continue check
          }
        )
      }
    }

    override def duration: Long = maxWait.toMillis

    override def calculateSleepTime(currentAttempt: Int): Long = 5000
  }
}