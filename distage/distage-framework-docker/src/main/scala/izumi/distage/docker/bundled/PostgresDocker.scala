package izumi.distage.docker.bundled

import izumi.distage.docker.ContainerDef
import izumi.distage.docker.model.Docker.DockerPort
import izumi.distage.docker.healthcheck.ContainerHealthCheck
import izumi.distage.model.definition.ModuleDef
import izumi.reflect.TagK

/**
  * Example postgres docker. It's sufficient for most usages.
  * You're encouraged to use this definition as a template and modify it to your needs.
  */
object PostgresDocker extends ContainerDef {
  val primaryPort: DockerPort = DockerPort.TCP(5432)

  override def config: Config = {
    Config(
      registry = Some("public.ecr.aws"),
      image = "docker/library/postgres:16-alpine",
      ports = Seq(primaryPort),
      env = Map("POSTGRES_PASSWORD" -> "postgres"),
      healthCheck = ContainerHealthCheck.postgreSqlProtocolCheck(primaryPort, "postgres", "postgres"),
    )
  }
}

class PostgresDockerModule[F[_]: TagK] extends ModuleDef {
  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[F]
  }
}

object PostgresDockerModule {
  def apply[F[_]: TagK]: PostgresDockerModule[F] = new PostgresDockerModule[F]
}
