package frankenpaxos.epaxos

import frankenpaxos.simulator.FakeLogger
import frankenpaxos.simulator.FakeTransport
import frankenpaxos.simulator.FakeTransportAddress
import frankenpaxos.simulator.SimulatedSystem
import org.scalacheck
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import scala.collection.mutable

class EPaxos(val f: Int) {
  val logger = new FakeLogger()
  val transport = new FakeTransport(logger)
  val numClients = f + 1
  val numReplicas = 2 * f + 1

  // Configuration.
  val config = Config[FakeTransport](
    f = f,
    replicaAddresses = for (i <- 1 to numReplicas)
      yield FakeTransportAddress(s"Replica $i")
  )

  // Clients.
  val clients = for (i <- 1 to numClients)
    yield
      new Client[FakeTransport](FakeTransportAddress(s"Client $i"),
                                transport,
                                logger,
                                config)

  // Replicas
  val replicas = for (i <- 1 to numReplicas)
    yield
      new Replica[FakeTransport](FakeTransportAddress(s"Replica $i"),
                                 transport,
                                 logger,
                                 config,
                                 new KeyValueStore(),
                                 new JgraphtDependencyGraph())
}

object SimulatedEPaxos {
  sealed trait Command
  case class Propose(clientIndex: Int, value: KeyValueStoreInput)
      extends Command
  case class TransportCommand(command: FakeTransport.Command) extends Command
}

class SimulatedEPaxos(val f: Int) extends SimulatedSystem {
  import SimulatedEPaxos._

  override type System = EPaxos
  // TODO(mwhittaker): Implement.
  override type State = Map[Instance, Set[Replica.CommandTriple]]
  override type Command = SimulatedEPaxos.Command

  override def newSystem(): System = new EPaxos(f)

  override def getState(epaxos: System): State = {
    // Merge two States together, taking a pairwise union.
    def merge(lhs: State, rhs: State): State = {
      val merged = for (k <- lhs.keys ++ rhs.keys)
        yield {
          k -> lhs.getOrElse(k, Set()).union(rhs.getOrElse(k, Set()))
        }
      Map(merged.toSeq: _*)
    }

    // We look at the commands recorded chosen by the replicas.
    epaxos.replicas
      .map(replica => Map() ++ replica.cmdLog)
      .map(cmdLog => {
        cmdLog.flatMap({
          case (i, Replica.CommittedEntry(triple)) => Some(i -> triple)
          case _                                   => None
        })
      })
      .map(cmdLog => cmdLog.mapValues(Set[Replica.CommandTriple](_)))
      .foldLeft(Map[Instance, Set[Replica.CommandTriple]]())(merge(_, _))
  }

  override def generateCommand(epaxos: System): Option[Command] = {
    def get(key: String): KeyValueStoreInput =
      KeyValueStoreInput().withGetRequest(GetRequest(key = Seq(key)))

    def set(key: String, value: String): KeyValueStoreInput =
      KeyValueStoreInput()
        .withSetRequest(SetRequest(keyValue = Seq(SetKeyValuePair(key, value))))

    val keys = Seq("a", "b", "c", "d")
    val requests = keys.map(get(_)) ++ keys.map(set(_, "foo"))

    val subgens = mutable.Buffer[(Int, Gen[Command])](
      // Propose.
      epaxos.numClients -> {
        for {
          clientId <- Gen.choose(0, epaxos.numClients - 1)
          request <- Gen.oneOf(requests)
        } yield Propose(clientId, request)
      }
    )
    FakeTransport
      .generateCommandWithFrequency(epaxos.transport)
      .foreach({
        case (frequency, gen) =>
          subgens += frequency -> gen.map(TransportCommand(_))
      })

    val gen: Gen[Command] = Gen.frequency(subgens: _*)
    gen.apply(Gen.Parameters.default, Seed.random())
  }

  override def runCommand(epaxos: System, command: Command): System = {
    command match {
      case Propose(clientId, request) =>
        epaxos.clients(clientId).propose(request.toByteArray)
      case TransportCommand(command) =>
        FakeTransport.runCommand(epaxos.transport, command)
    }
    epaxos
  }

  override def stateInvariantHolds(
      state: State
  ): SimulatedSystem.InvariantResult = {
    // Every instance has a single committed entry.
    for ((instance, chosen) <- state) {
      if (chosen.size > 1) {
        return SimulatedSystem.InvariantViolated(
          s"Instance $instance has multiple chosen values: $chosen."
        )
      }
    }

    // Every pair of conflicting instances has a dependency on each other.
    for ((instanceA, chosenA) <- state if chosenA.size > 0) {
      for {
        (instanceB, chosenB) <- state
        if instanceA != instanceB
        if chosenB.size > 0
      } {
        val Replica.CommandTriple(commandOrNoopA, _, depsA) = chosenA.head
        val Replica.CommandTriple(commandOrNoopB, _, depsB) = chosenB.head

        import CommandOrNoop.Value
        (commandOrNoopA.value, commandOrNoopB.value) match {
          case (Value.Command(commandA), Value.Command(commandB)) =>
            if (new KeyValueStore().conflicts(commandA.command.toByteArray,
                                              commandB.command.toByteArray) &&
                !depsA.contains(instanceB) &&
                !depsB.contains(instanceA)) {
              return SimulatedSystem.InvariantViolated(
                s"Instances $instanceA and $instanceB conflict but do not " +
                  s"depend on each other (dependencies $depsA and $depsB)."
              )
            }

          case (Value.Empty, _) | (_, Value.Empty) =>
            return SimulatedSystem.InvariantViolated(
              s"Empty CommandOrNoop found."
            )

          case (Value.Noop(_), _) | (_, Value.Noop(_)) =>
          // Nothing to check.
        }
      }
    }

    SimulatedSystem.InvariantHolds
  }

  override def stepInvariantHolds(
      oldState: State,
      newState: State
  ): SimulatedSystem.InvariantResult = {
    for (instance <- oldState.keys ++ newState.keys) {
      val oldChosen = oldState.getOrElse(instance, Set[Replica.CommandTriple]())
      val newChosen = newState.getOrElse(instance, Set[Replica.CommandTriple]())
      if (!oldChosen.subsetOf(newChosen)) {
        SimulatedSystem.InvariantViolated(
          s"Instance $instance was $oldChosen but now is $newChosen."
        )
      }
    }
    SimulatedSystem.InvariantHolds
  }

  def commandToString(command: Command): String = {
    val epaxos = newSystem()
    command match {
      case Propose(clientIndex, value) =>
        val clientAddress = epaxos.clients(clientIndex).address.address
        s"Propose($clientAddress, $value)"

      case TransportCommand(FakeTransport.DeliverMessage(msg)) =>
        val dstActor = epaxos.transport.actors(msg.dst)
        val s = dstActor.serializer.toPrettyString(
          dstActor.serializer.fromBytes(msg.bytes.to[Array])
        )
        s"DeliverMessage(src=${msg.src.address}, dst=${msg.dst.address})\n$s"

      case TransportCommand(FakeTransport.TriggerTimer(address, name, id)) =>
        s"TriggerTimer(${address.address}:$name ($id))"
    }
  }

  def historyToString(history: Seq[Command]): String = {
    def indent(s: String, n: Int): String = {
      s.replaceAll("\n", "\n" + " " * n)
    }
    history.zipWithIndex
      .map({
        case (command, i) =>
          val num = "%3d".format(i)
          s"$num. ${indent(commandToString(command), 5)}"
      })
      .mkString("\n")
  }
}
