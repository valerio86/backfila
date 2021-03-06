package app.cash.backfila.actions

import app.cash.backfila.BackfilaTestingModule
import app.cash.backfila.api.ConfigureServiceAction
import app.cash.backfila.client.Connectors
import app.cash.backfila.dashboard.CreateBackfillAction
import app.cash.backfila.dashboard.CreateBackfillRequest
import app.cash.backfila.dashboard.GetBackfillRunsAction
import app.cash.backfila.dashboard.GetBackfillStatusAction
import app.cash.backfila.dashboard.StartBackfillAction
import app.cash.backfila.dashboard.StartBackfillRequest
import app.cash.backfila.dashboard.StopBackfillAction
import app.cash.backfila.dashboard.StopBackfillRequest
import app.cash.backfila.fakeCaller
import app.cash.backfila.protos.service.ConfigureServiceRequest
import app.cash.backfila.service.BackfilaDb
import app.cash.backfila.service.BackfillState
import app.cash.backfila.service.DbBackfillRun
import com.google.inject.Module
import misk.exceptions.BadRequestException
import misk.hibernate.Id
import misk.hibernate.Query
import misk.hibernate.Transacter
import misk.hibernate.load
import misk.scope.ActionScope
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import javax.inject.Inject
import kotlin.test.assertNotNull

@MiskTest(startService = true)
class StartStopBackfillActionTest {
  @Suppress("unused")
  @MiskTestModule
  val module: Module = BackfilaTestingModule()

  @Inject lateinit var configureServiceAction: ConfigureServiceAction
  @Inject lateinit var createBackfillAction: CreateBackfillAction
  @Inject lateinit var startBackfillAction: StartBackfillAction
  @Inject lateinit var stopBackfillAction: StopBackfillAction
  @Inject lateinit var getBackfillRunsAction: GetBackfillRunsAction
  @Inject lateinit var getBackfillStatusAction: GetBackfillStatusAction
  @Inject lateinit var queryFactory: Query.Factory
  @Inject lateinit var scope: ActionScope
  @Inject @BackfilaDb lateinit var transacter: Transacter

  @Test
  fun startAndStop() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(Connectors.ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      var backfillRuns = getBackfillRunsAction.backfillRuns("deep-fryer")
      assertThat(backfillRuns.paused_backfills).hasSize(0)
      assertThat(backfillRuns.running_backfills).hasSize(0)

      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))

      backfillRuns = getBackfillRunsAction.backfillRuns("deep-fryer")
      assertThat(backfillRuns.paused_backfills).hasSize(1)
      assertThat(backfillRuns.running_backfills).hasSize(0)

      val id = response.id
      assertThat(backfillRuns.paused_backfills[0].id).isEqualTo(id.toString())
      startBackfillAction.start(id, StartBackfillRequest())

      var status = getBackfillStatusAction.status(id)
      assertThat(status.state).isEqualTo(BackfillState.RUNNING)
      assertThat(status.instances.map { it.state })
          .containsOnly(BackfillState.RUNNING)

      backfillRuns = getBackfillRunsAction.backfillRuns("deep-fryer")
      assertThat(backfillRuns.paused_backfills).hasSize(0)
      assertThat(backfillRuns.running_backfills).hasSize(1)

      stopBackfillAction.stop(id, StopBackfillRequest())

      status = getBackfillStatusAction.status(id)
      assertThat(status.state).isEqualTo(BackfillState.PAUSED)
      assertThat(status.instances.map { it.state })
          .containsOnly(BackfillState.PAUSED)

      backfillRuns = getBackfillRunsAction.backfillRuns("deep-fryer")
      assertThat(backfillRuns.paused_backfills).hasSize(1)
      assertThat(backfillRuns.running_backfills).hasSize(0)
    }
  }

  @Test
  fun pagination() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(
          ConfigureServiceRequest.Builder()
              .backfills(
                  listOf(
                      ConfigureServiceRequest.BackfillData.Builder()
                          .name("ChickenSandwich")
                          .description("Description")
                          .build(),
                      ConfigureServiceRequest.BackfillData.Builder()
                          .name("BeefSandwich")
                          .description("Description")
                          .build()
                  )
              )
              .connector_type(Connectors.ENVOY)
              .build()
      )
    }
    scope.fakeCaller(user = "molly") {
      repeat(15) {
        createBackfillAction.create(
            "deep-fryer",
            CreateBackfillRequest("ChickenSandwich")
        )
        createBackfillAction.create(
            "deep-fryer",
            CreateBackfillRequest("BeefSandwich")
        )
      }
      val backfillRuns = getBackfillRunsAction.backfillRuns("deep-fryer")
      assertThat(backfillRuns.paused_backfills).hasSize(20)

      val backfillRunsPage2 = getBackfillRunsAction.backfillRuns(
          "deep-fryer",
          pagination_token = backfillRuns.next_pagination_token
      )
      assertThat(backfillRunsPage2.paused_backfills).hasSize(10)
    }
  }

  @Test
  fun backfillDoesntExist() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(
          ConfigureServiceRequest.Builder()
              .backfills(
                  listOf(
                      ConfigureServiceRequest.BackfillData(
                          "ChickenSandwich", "Description", listOf(), null,
                          null, false
                      )
                  )
              )
              .connector_type(Connectors.ENVOY)
              .build()
      )
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))
      val id = response.id

      assertThatThrownBy {
        startBackfillAction.start(id + 1, StartBackfillRequest())
      }.isInstanceOf(BadRequestException::class.java)
    }
  }

  @Test
  fun cantStartRunningBackfill() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(Connectors.ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))
      val id = response.id
      startBackfillAction.start(id, StartBackfillRequest())

      transacter.transaction { session ->
        val run = session.load(Id<DbBackfillRun>(id))
        assertNotNull(run)
        assertThat(run.state).isEqualTo(BackfillState.RUNNING)
      }

      assertThatThrownBy {
        startBackfillAction.start(id, StartBackfillRequest())
      }.isInstanceOf(BadRequestException::class.java)
    }
  }

  @Test
  fun cantStopPausedBackfill() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(Connectors.ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))
      val id = response.id
      assertThatThrownBy {
        stopBackfillAction.stop(id, StopBackfillRequest())
      }.isInstanceOf(BadRequestException::class.java)
    }
  }

  @Test
  fun cantToggleCompletedBackfill() {
    scope.fakeCaller(service = "deep-fryer") {
      configureServiceAction.configureService(ConfigureServiceRequest.Builder()
          .backfills(listOf(
              ConfigureServiceRequest.BackfillData("ChickenSandwich", "Description", listOf(), null,
                  null, false)))
          .connector_type(Connectors.ENVOY)
          .build())
    }
    scope.fakeCaller(user = "molly") {
      val response = createBackfillAction.create("deep-fryer",
          CreateBackfillRequest("ChickenSandwich"))
      val id = response.id

      transacter.transaction { session ->
        val run = session.load(Id<DbBackfillRun>(id))
        assertNotNull(run)
        run.setState(session, queryFactory, BackfillState.COMPLETE)
      }

      assertThatThrownBy {
        startBackfillAction.start(id, StartBackfillRequest())
      }.isInstanceOf(BadRequestException::class.java)
      assertThatThrownBy {
        stopBackfillAction.stop(id, StopBackfillRequest())
      }.isInstanceOf(BadRequestException::class.java)
    }
  }
}
