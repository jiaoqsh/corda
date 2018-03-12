package net.corda.node.modes.draining

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.map
import net.corda.core.messaging.StateMachineUpdate
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.unwrap
import net.corda.node.services.Permissions
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.subjects.PublishSubject
import java.time.Duration
import java.util.concurrent.*
import kotlin.test.fail

class P2PFlowsDrainingModeTest {

    private val portAllocation = PortAllocation.Incremental(10000)
    private val user = User("mark", "dadada", setOf(Permissions.all()))
    private val users = listOf(user)

    private var executor: ScheduledExecutorService? = null

    companion object {
        private val logger = loggerFor<P2PFlowsDrainingModeTest>()
    }

    @Before
    fun setup() {
        executor = Executors.newSingleThreadScheduledExecutor()
    }

    @After
    fun cleanUp() {
        executor!!.shutdown()
    }

    @Test
    fun `flows draining mode suspends consumption of initial session messages`() {

        driver(DriverParameters(isDebug = true, startNodesInProcess = false, portAllocation = portAllocation)) {

            val initiatedNode = startNode().getOrThrow()
            val initiating = startNode(rpcUsers = users).getOrThrow().rpc
            val counterParty = initiatedNode.nodeInfo.singleIdentity()
            val initiated = initiatedNode.rpc

            initiated.setFlowsDrainingModeEnabled(true)

            var shouldFail = true
            initiating.apply {
                val flow = startFlow(::InitiateSessionFlow, counterParty)
                // this should be really fast, for the flow has already started, so 5 seconds should never be a problem
                executor!!.schedule({
                    logger.info("Now disabling flows draining mode for $counterParty.")
                    shouldFail = false
                    initiated.setFlowsDrainingModeEnabled(false)
                }, 5, TimeUnit.SECONDS)
                flow.returnValue.map { result ->
                    if (shouldFail) {
                        fail("Shouldn't happen until flows draining mode is switched off.")
                    } else {
                        assertThat(result).isEqualTo("Hi there answer")
                    }
                }.getOrThrow()
            }
        }
    }

    @Test
    fun `clean shutdown by draining`() {

        driver(DriverParameters(isDebug = true, startNodesInProcess = true, portAllocation = portAllocation)) {

            val initiatedNode = startNode().getOrThrow()
            val initiating = startNode(rpcUsers = users).getOrThrow().rpc
            val counterParty = initiatedNode.nodeInfo.singleIdentity()
            val initiated = initiatedNode.rpc

            initiated.setFlowsDrainingModeEnabled(true)

            var shouldFail = true

            val stateMachineState = initiated.stateMachinesFeed()
            var pendingFlowsCount = stateMachineState.snapshot.size
            stateMachineState
                    .updates
                    .doOnNext { update ->
                        when (update) {
                            is StateMachineUpdate.Added -> {
                                pendingFlowsCount++
                            }
                            is StateMachineUpdate.Removed -> {
                                pendingFlowsCount--
                                if (pendingFlowsCount == 0) {
                                    shouldFail = false
                                    initiated.shutdown()
                                }
                            }
                        }
                    }.subscribe()

            initiating.stateMachinesFeed().updates.filter { it is StateMachineUpdate.Added }.doOnNext { initiated.setFlowsDrainingModeEnabled(false) }.subscribe()

            val latch = CountDownLatch(1)

            initiatedNode.shutdownEvent()
                    .doOnError { error ->
                        error.printStackTrace()
                        shouldFail = true
                    }
                    .doAfterTerminate {
                        latch.countDown()
                    }
                    .subscribe()

            initiating.startFlow(::InitiateSessionFlow, counterParty)

            latch.await()
            assertThat(shouldFail).isFalse()
        }
    }
}

private fun NodeHandle.shutdownEvent(user: User = rpcUsers[0], period: Duration = Duration.ofSeconds(1)): Observable<Unit> {

    val nodeIsShut: PublishSubject<Unit> = PublishSubject.create()
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val client = rpcClient()

    var task: ScheduledFuture<*>? = null
    return nodeIsShut
            .doOnSubscribe {
                task = scheduler.scheduleAtFixedRate({
                    try {
                        client.start(user).use {
                            // just close the connection
                        }
                    } catch (e: ActiveMQNotConnectedException) {
                        // not cool here, for the connection might be interrupted without the node actually getting shut down - OK for tests
                        nodeIsShut.onCompleted()
                    } catch (e: ActiveMQSecurityException) {
                        // nothing here - this happens if trying to connect before the node is started
                    } catch (e: Throwable) {
                        nodeIsShut.onError(e)
                    }
                }, 1, period.toMillis(), TimeUnit.MILLISECONDS)
            }
            .doAfterTerminate {
                task?.cancel(true)
                scheduler.shutdown()
            }
}

private fun CordaRPCClient.start(user: User) = start(user.username, user.password)

private fun NodeHandle.rpcClient(configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default()) = CordaRPCClient(rpcAddress, configuration)

@StartableByRPC
@InitiatingFlow
class InitiateSessionFlow(private val counterParty: Party) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        val session = initiateFlow(counterParty)
        session.send("Hi there")
        return session.receive<String>().unwrap { it }
    }
}

@InitiatedBy(InitiateSessionFlow::class)
class InitiatedFlow(private val initiatingSession: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {

        val message = initiatingSession.receive<String>().unwrap { it }
        initiatingSession.send("$message answer")
    }
}