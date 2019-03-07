package frankenpaxos.fastmultipaxos

import collection.immutable.SortedMap
import collection.mutable
import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.Util
import frankenpaxos.statemachine.StateMachine
import scala.collection.breakOut
import scala.scalajs.js.annotation._

@JSExportAll
object LeaderInboundSerializer extends ProtoSerializer[LeaderInbound] {
  type A = LeaderInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

@JSExportAll
object Leader {
  val serializer = LeaderInboundSerializer
}

@JSExportAll
class Leader[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    val stateMachine: StateMachine
) extends Actor(address, transport, logger) {
  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = LeaderInbound
  override val serializer = LeaderInboundSerializer

  type AcceptorId = Int
  type Round = Int
  type Slot = Int

  // Fields ////////////////////////////////////////////////////////////////////
  // Sanity check the Paxos configuration and compute the leader's id.
  logger.check(config.leaderAddresses.contains(address))
  private val leaderId = config.leaderAddresses.indexOf(address)

  // Channels to all other leaders.
  private val otherLeaders: Seq[Chan[Leader[Transport]]] =
    for (a <- config.leaderAddresses if a != address)
      yield chan[Leader[Transport]](a, Leader.serializer)

  // Channels to all the acceptors.
  private val acceptors: Seq[Chan[Acceptor[Transport]]] =
    for (address <- config.acceptorAddresses)
      yield chan[Acceptor[Transport]](address, Acceptor.serializer)

  // The current round. Initially, the leader that owns round 0 is the active
  // leader, and all other leaders are inactive.
  @JSExport
  protected var round: Round =
    if (config.roundSystem.leader(0) == leaderId) 0 else -1

  // The log of chosen commands.
  sealed trait Entry
  case class ECommand(command: Command) extends Entry
  case object ENoop extends Entry

  @JSExport
  protected val log: mutable.SortedMap[Slot, Entry] = mutable.SortedMap()

  // The client table records the response to the latest request from each
  // client. For example, if command c1 sends command x with id 2 to a leader
  // and the leader later executes x yielding response y, then c1 maps to (2,
  // y) in the client table.
  @JSExport
  protected var clientTable =
    mutable.Map[Transport#Address, (Int, Array[Byte])]()

  // At any point in time, the leader knows that all slots less than
  // chosenWatermark have been chosen. That is, for every `slot` <
  // chosenWatermark, there is an Entry for `slot` in `log`.
  @JSExport
  protected var chosenWatermark: Slot = 0

  // The next slot in which to propose a command.
  //
  // TODO(mwhittaker): Add a buffer to prevent the leader from running too far
  // ahead.
  @JSExport
  protected var nextSlot: Slot = 0

  // The state of the leader.
  @JSExportAll
  sealed trait State

  // This leader is not the active leader.
  @JSExportAll
  case object Inactive extends State

  // This leader is executing phase 1.
  @JSExportAll
  case class Phase1(
      // Phase 1b responses.
      phase1bs: mutable.Map[AcceptorId, Phase1b],
      // Pending proposals. When a leader receives a proposal during phase 1,
      // it buffers the proposal and replays it once it enters phase 2.
      pendingProposals: mutable.Buffer[(Transport#Address, ProposeRequest)],
      // A timer to resend phase 1as.
      resendPhase1as: Transport#Timer
  ) extends State

  private val resendPhase1asTimer: Transport#Timer = timer(
    "resendPhase1as",
    // TODO(mwhittaker): Pass in as parameter.
    java.time.Duration.ofSeconds(3),
    () => {
      sendPhase1as()
      resendPhase1asTimer.start()
    }
  )

  // This leader has finished executing phase 1 and is now executing phase 2.
  @JSExportAll
  case class Phase2(
      // In a classic round, leaders receive commands from clients and relay
      // them on to acceptors. pendingEntries stores these commands that are
      // pending votes. Note that during a fast round, a leader may not have a
      // pending command for a slot, even though it does have phase 2bs for it.
      pendingEntries: mutable.SortedMap[Slot, Entry],
      // For each slot, the set of phase 2b messages for that slot. In a
      // classic round, all the phase 2b messages will be for the same command.
      // In a fast round, they do not necessarily have to be.
      phase2bs: mutable.SortedMap[Slot, mutable.Map[AcceptorId, Phase2b]],
      // A timer to resend all pending phase 2a messages.
      resendPhase2as: Transport#Timer
  ) extends State

  private val resendPhase2asTimer: Transport#Timer = timer(
    "resendPhase2as",
    // TODO(mwhittaker): Pass in as parameter.
    java.time.Duration.ofSeconds(3),
    () => {
      resendPhase2as()
      resendPhase2asTimer.start()
    }
  )

  @JSExport
  protected var state: State =
    if (round == 0) {
      sendPhase1as()
      resendPhase1asTimer.start()
      Phase1(mutable.Map(), mutable.Buffer(), resendPhase1asTimer)
    } else {
      Inactive
    }

  // Leaders participate in a leader election protocol to maintain a
  // (hopefully) stable leader.
  //
  // TODO(mwhittaker): Pass in leader election options.
  @JSExport
  protected val electionAddress: Transport#Address =
    config.leaderElectionAddresses(leaderId)
  @JSExport
  protected val election: frankenpaxos.election.Participant[Transport] =
    new frankenpaxos.election.Participant[Transport](
      electionAddress,
      transport,
      logger,
      config.leaderElectionAddresses.to[Set],
      leader = Some(
        config.leaderElectionAddresses(config.roundSystem.leader(0))
      )
    )
  // TODO(mwhittaker): Is this thread safe? It's possible that the election
  // participant invokes the callback before this leader has finished
  // initializing?
  election.register((address) => {
    // The address returned by the election participant is the address of the
    // election participant, not of the leader.
    val leaderAddress =
      config.leaderAddresses(config.leaderElectionAddresses.indexOf(address))
    leaderChange(leaderAddress)
  })

  // Leaders monitor acceptors to make sure they are still alive.
  //
  // TODO(mwhittaker): Pass in hearbeat options.
  @JSExport
  protected val heartbeatAddress: Transport#Address =
    config.leaderHeartbeatAddresses(leaderId)
  @JSExport
  protected val heartbeat: frankenpaxos.heartbeat.Participant[Transport] =
    new frankenpaxos.heartbeat.Participant[Transport](
      heartbeatAddress,
      transport,
      logger,
      config.acceptorHeartbeatAddresses.to[Set]
    )

  // Methods ///////////////////////////////////////////////////////////////////
  private def sendPhase1as(): Unit = {
    for (acceptor <- acceptors) {
      acceptor.send(
        AcceptorInbound().withPhase1A(
          Phase1a(round = round,
                  chosenWatermark = chosenWatermark,
                  chosenSlot = log.keysIteratorFrom(chosenWatermark).to[Seq])
        )
      )
    }
  }

  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import LeaderInbound.Request
    inbound.request match {
      case Request.ProposeRequest(r) => handleProposeRequest(src, r)
      case Request.Phase1B(r)        => handlePhase1b(src, r)
      case Request.Phase2B(r)        => handlePhase2b(src, r)
      case Request.ValueChosen(r)    => handleValueChosen(src, r)
      case Request.Empty =>
        logger.fatal("Empty LeaderInbound encountered.")
    }
  }

  private def handleProposeRequest(
      src: Transport#Address,
      request: ProposeRequest
  ): Unit = {
    val client = chan[Client[Transport]](src, Client.serializer)

    // If we've cached the result of this proposed command in the client table,
    // then we can reply to the client directly. Note that only the leader
    // replies to the client since ProposeReplies include the round of the
    // leader, and only the leader knows this.
    clientTable.get(src) match {
      case Some((clientId, result)) =>
        if (request.command.clientId == clientId && state != Inactive) {
          logger.debug(
            s"The latest command for client $src (i.e., command $clientId)" +
              s"was found in the client table."
          )
          client.send(
            ClientInbound().withProposeReply(
              ProposeReply(round = round,
                           clientId = clientId,
                           result = ByteString.copyFrom(result))
            )
          )
        }
        return
      case None =>
    }

    state match {
      case Inactive =>
        logger.debug("Leader received propose request but is inactive.")

      case Phase1(_, pendingProposals, _) =>
        if (request.round != round) {
          // We don't want to process requests from out of date clients.
          logger.debug(
            s"Leader received a propose request in round " +
              s"${request.round}, but is in round $round."
          )
          client.send(ClientInbound().withLeaderInfo(LeaderInfo(round)))
        } else {
          // We buffer all pending proposals in phase 1 and process them later
          // when we enter phase 2.
          pendingProposals += ((src, request))
        }

      case Phase2(pendingEntries, phase2bs, _) =>
        if (request.round != round) {
          // We don't want to process requests from out of date clients.
          logger.debug(
            s"Leader received a propose request in round " +
              s"${request.round}, but is in round $round."
          )
          client.send(ClientInbound().withLeaderInfo(LeaderInfo(round)))
          return
        }

        config.roundSystem.roundType(round) match {
          case ClassicRound =>
            val phase2a = Phase2a(slot = nextSlot, round = round)
              .withCommand(request.command)
            acceptors.foreach(_.send(AcceptorInbound().withPhase2A(phase2a)))
            pendingEntries(nextSlot) = ECommand(request.command)
            phase2bs(nextSlot) = mutable.Map()
            nextSlot += 1

          case FastRound =>
            // If we're in a fast round, and the client knows we're in a fast
            // round (because request.round == round), then the client should
            // not be sending the leader any requests. It only does so if there
            // was a failure. In this case, we change to a higher round.
            leaderChange(address)
        }
    }
  }

  // Given a quorum of phase1b votes, determine a safe value to propose in slot
  // `slot`.
  def chooseProposal(
      votes: collection.Map[AcceptorId, SortedMap[Slot, Phase1bVote]],
      slot: Slot
  ): Entry = {
    def phase1bVoteValueToEntry(voteValue: Phase1bVote.Value): Entry = {
      import Phase1bVote.Value
      voteValue match {
        case Value.Command(command) => ECommand(command)
        case Value.Noop(_)          => ENoop
        case Value.Empty =>
          logger.fatal("Empty Phase1bVote.Value.")
          ???
      }
    }

    val votesInSlot = votes.keys.map(
      (a) =>
        votes(a).get(slot) match {
          case Some(vote) => (vote.voteRound, Some(vote.value))
          case None       => (-1, None)
        }
    )
    val k = votesInSlot.map({ case (voteRound, _) => voteRound }).max
    val V = votesInSlot
      .filter({ case (voteRound, _) => voteRound == k })
      .map({ case (_, voteValue) => voteValue })

    // If no acceptor has voted yet, we're free to propose anything. Here, we
    // propose noop.
    if (k == -1) {
      return ENoop
    }

    // If V = {v} is a singleton set, then we must propose v.
    if (V.to[Set].size == 1) {
      return phase1bVoteValueToEntry(V.head.get)
    }

    // If there exists a v in V such that O4(v), then we must propose it.
    val o4vs = frankenpaxos.Util.popularItems(V, config.quorumMajoritySize)
    if (o4vs.size > 0) {
      logger.check_eq(o4vs.size, 1)
      return phase1bVoteValueToEntry(o4vs.head.get)
    }

    ENoop
  }

  private def handlePhase1b(
      src: Transport#Address,
      request: Phase1b
  ): Unit = {
    state match {
      case Inactive | Phase2(_, _, _) =>
        logger.debug("Leader received phase 1b, but is not in phase 1.")

      case Phase1(phase1bs, pendingProposals, resendPhase1as) =>
        if (request.round != round) {
          logger.debug(
            s"eader received phase 1b in round ${request.round}, but is in" +
              s"round $round."
          )
          return
        }

        // Wait until we receive a quorum of phase 1bs.
        phase1bs(request.acceptorId) = request
        if (phase1bs.size < config.classicQuorumSize) {
          logger.debug(
            s"Leader does not have enough phase 1b votes yet. It has " +
              s"${phase1bs.size} but needs ${config.classicQuorumSize}."
          )
          return
        }

        // If we do have a quorum of phase 1bs, then we transition to phase 2.
        resendPhase1as.stop()

        // `phase1bs` maps each acceptor to a list of phase1b votes. We index
        // each of these lists by slot.
        type VotesBySlot = SortedMap[Slot, Phase1bVote]
        val votes: collection.Map[AcceptorId, VotesBySlot] =
          phase1bs.mapValues((phase1b) => {
            phase1b.vote.map(vote => vote.slot -> vote)(breakOut): VotesBySlot
          })

        // The leader's log contains chosen entries for some slots, and the
        // acceptors have voted for some slots. This looks something like this:
        //
        //                                     chosenWatermark
        //                                    /                   endSlot
        //                                   /                   /
        //                      0   1   2   3   4   5   6   7   8   9
        //                    +---+---+---+---+---+---+---+---+---+---+
        //               log: | x | x | x |   |   | x |   |   | x |   |
        //                    +---+---+---+---+---+---+---+---+---+---+
        //   acceptor 0 vote:               x   x
        //   acceptor 1 vote:                   x       x
        //   acceptor 2 vote:               x       x   x
        //
        // The leader does not want gaps in the log, so it attempts to choose
        // as many slots as possible to remove the gaps. In the example above,
        // the leader would propose in slots 3, 4, 6, and 7. Letting endSlot =
        // 8, these are the unchosen slots in the range [chosenWatermark,
        // endSlot].
        //
        // In the example above, endSlot is 8 because it is the largest chosen
        // slot. However, in the example below, it is 9 because an acceptor has
        // voted in slot 9. Thus, we let endSlot be the larger of (a) the
        // largest chosen slot and (b) the largest slot with a phase1b vote.
        //
        //                                     chosenWatermark
        //                                    /                       endSlot
        //                                   /                       /
        //                      0   1   2   3   4   5   6   7   8   9
        //                    +---+---+---+---+---+---+---+---+---+---+
        //               log: | x | x | x |   |   | x |   |   | x |   |
        //                    +---+---+---+---+---+---+---+---+---+---+
        //   acceptor 0 vote:               x   x                   x
        //   acceptor 1 vote:                   x       x   x
        //   acceptor 2 vote:               x       x   x           x
        val endSlot: Int = math.max(
          votes
            .map({ case (a, vs) => if (vs.size == 0) -1 else vs.lastKey })
            .max,
          if (log.size == 0) -1 else log.lastKey
        )
        logger.debug(s"Leader chosenWatermark = $chosenWatermark.")
        logger.debug(s"Leader endSlot = $endSlot.")

        // For every unchosen slot between the chosenWatermark and endSlot,
        // choose a value to propose and propose it.
        val pendingEntries = mutable.SortedMap[Slot, Entry]()
        val phase2bs =
          mutable.SortedMap[Slot, mutable.Map[AcceptorId, Phase2b]]()
        for (slot <- chosenWatermark to endSlot) {
          val proposal: Entry = chooseProposal(votes, slot)
          val phase2a = Phase2a(slot = slot, round = round)
          val msg = proposal match {
            case ECommand(command) =>
              AcceptorInbound().withPhase2A(phase2a.withCommand(command))
            case ENoop =>
              AcceptorInbound().withPhase2A(phase2a.withNoop(Noop()))
          }

          pendingEntries(slot) = proposal
          phase2bs(slot) = mutable.Map[AcceptorId, Phase2b]()
          acceptors.foreach(_.send(msg))
        }

        state = Phase2(pendingEntries, phase2bs, resendPhase2asTimer)
        resendPhase2asTimer.start()

        // Replay the pending proposals.
        nextSlot = endSlot + 1
        for ((src, proposal) <- pendingProposals) {
          handleProposeRequest(src, proposal)
        }

        // If this is a fast round, send a suffix of anys.
        if (config.roundSystem.roundType(round) == FastRound) {
          val msg = AcceptorInbound().withPhase2A(
            Phase2a(slot = nextSlot, round = round)
              .withAnySuffix(AnyValSuffix())
          )
          acceptors.foreach(_.send(msg))
        }
    }
  }

  private def phase2bVoteToEntry(phase2bVote: Phase2b.Vote): Entry = {
    phase2bVote match {
      case Phase2b.Vote.Command(command) => ECommand(command)
      case Phase2b.Vote.Noop(_)          => ENoop
      case Phase2b.Vote.Empty =>
        logger.fatal("Empty Phase2b.Vote")
        ???
    }
  }

  sealed trait Phase2bVoteResult
  case object NothingReadyYet extends Phase2bVoteResult
  case class ClassicReady(entry: Entry) extends Phase2bVoteResult
  case class FastReady(entry: Entry) extends Phase2bVoteResult
  case object FastStuck extends Phase2bVoteResult

  // TODO(mwhittaker): Document.
  private def phase2bChosenInSlot(
      phase2: Phase2,
      slot: Slot
  ): Phase2bVoteResult = {
    val Phase2(pendingEntries, phase2bs, _) = phase2

    config.roundSystem.roundType(round) match {
      case ClassicRound =>
        if (phase2bs
              .getOrElse(slot, mutable.Map())
              .size >= config.classicQuorumSize) {
          ClassicReady(pendingEntries(slot))
        } else {
          NothingReadyYet
        }

      case FastRound =>
        phase2bs.getOrElseUpdate(slot, mutable.Map())
        if (phase2bs(slot).size < config.classicQuorumSize) {
          return NothingReadyYet
        }

        val voteValueCounts = Util.histogram(phase2bs(slot).values.map(_.vote))

        // We've heard from `phase2bs(slot).size` acceptors. This means there
        // are `votesLeft = config.n - phase2bs(slot).size` acceptors left. In
        // order for a value to be choosable, it must be able to reach a fast
        // quorum of votes if all the `votesLeft` acceptors vote for it. If no
        // such value exists, no value can be chosen.
        val votesLeft = config.n - phase2bs(slot).size
        if (!voteValueCounts.exists({
              case (_, count) => count + votesLeft >= config.fastQuorumSize
            })) {
          return FastStuck
        }

        for ((voteValue, count) <- voteValueCounts) {
          if (count >= config.fastQuorumSize) {
            return FastReady(phase2bVoteToEntry(voteValue))
          }
        }

        NothingReadyYet
    }
  }

  private def handlePhase2b(
      src: Transport#Address,
      phase2b: Phase2b
  ): Unit = {
    def toValueChosen(slot: Slot, entry: Entry): ValueChosen = {
      entry match {
        case ECommand(command) =>
          ValueChosen(slot = slot).withCommand(command)
        case ENoop => ValueChosen(slot = slot).withNoop(Noop())
      }
    }

    state match {
      case Inactive | Phase1(_, _, _) =>
        logger.debug(
          "A leader received a phase 2b response but is not in phase 2"
        )

      case phase2 @ Phase2(pendingEntries, phase2bs, _) =>
        def choose(entry: Entry): Unit = {
          log(phase2b.slot) = entry
          pendingEntries -= phase2b.slot
          phase2bs -= phase2b.slot
          executeLog()
          for (leader <- otherLeaders) {
            leader.send(
              LeaderInbound()
                .withValueChosen(toValueChosen(phase2b.slot, entry))
            )
          }
        }

        // Ignore responses that are not in our current round.
        if (phase2b.round != round) {
          logger.debug(
            s"A leader received a phase 2b response for round " +
              s"${phase2b.round} but is in round ${round}."
          )
          return
        }

        // Wait for sufficiently many phase2b replies.
        phase2bs.getOrElseUpdate(phase2b.slot, mutable.Map())
        phase2bs(phase2b.slot).put(phase2b.acceptorId, phase2b)
        phase2bChosenInSlot(phase2, phase2b.slot) match {
          case NothingReadyYet =>
            // Don't do anything.
            logger.debug(s"Nothing ready yet in slot ${phase2b.slot}.")

          case ClassicReady(entry) =>
            logger.debug(s"A value has been chosen in ${phase2b.slot}.")
            choose(entry)

          case FastReady(entry) =>
            logger.debug(s"A value has been chosen in ${phase2b.slot}.")
            choose(entry)

          case FastStuck =>
            // The fast round is stuck, so we start again in a higher round.
            logger.debug(s"${phase2b.slot} is stuck.")
            leaderChange(address)
        }
    }
  }

  private def handleValueChosen(
      src: Transport#Address,
      valueChosen: ValueChosen
  ): Unit = {
    val entry = valueChosen.value match {
      case ValueChosen.Value.Command(command) => ECommand(command)
      case ValueChosen.Value.Noop(_)          => ENoop
      case ValueChosen.Value.Empty =>
        logger.fatal("Empty ValueChosen.Vote")
        ???
    }

    log.get(valueChosen.slot) match {
      case Some(existingEntry) =>
        logger.check_eq(entry, existingEntry)
      case None =>
        log(valueChosen.slot) = entry
        executeLog()
    }
  }

  def resendPhase2as(): Unit = {
    state match {
      case Inactive | Phase1(_, _, _) =>
        logger.fatal("Executing resendPhase2as not in phase 2.")

      case Phase2(pendingEntries, phase2bs, _) =>
        val endSlot: Int = math.max(
          phase2bs.keys.lastOption.getOrElse(-1),
          log.keys.lastOption.getOrElse(-1)
        )

        for (slot <- chosenWatermark to endSlot) {
          val entryToPhase2a: Entry => AcceptorInbound = {
            case ECommand(command) =>
              AcceptorInbound().withPhase2A(
                Phase2a(slot = slot, round = round).withCommand(command)
              )
            case ENoop =>
              AcceptorInbound().withPhase2A(
                Phase2a(slot = slot, round = round).withNoop(Noop())
              )
          }

          (pendingEntries.get(slot), phase2bs.get(slot)) match {
            case (Some(entry), _) =>
              // If we have some pending entry, then we propose that.
              acceptors.foreach(_.send(entryToPhase2a(entry)))

            case (None, Some(phase2bsInSlot)) =>
              // If there is no pending entry, then we propose the value with
              // the most votes so far. If no value has been voted, then we
              // just propose Noop.
              val voteValues = phase2bsInSlot.values.map(_.vote)
              val histogram = Util.histogram(voteValues)
              if (voteValues.size == 0) {
                acceptors.foreach(_.send(entryToPhase2a(ENoop)))
              } else {
                val mostVoted = histogram.maxBy(_._2)._1
                val msg = entryToPhase2a(phase2bVoteToEntry(mostVoted))
                acceptors.foreach(_.send(msg))
              }

            case (None, None) =>
              acceptors.foreach(_.send(entryToPhase2a(ENoop)))
          }
        }
    }
  }

  // Switch over to a new leader. If the new leader is ourselves, then we
  // increase our round and enter a new round.
  def leaderChange(leader: Transport#Address): Unit = {
    // Try to go to a fast round if we think that a fast quorum of acceptors
    // are alive. If we think fewer than a fast quorum of acceptors are alive,
    // then proceed to a classic round.
    val nextRound = if (heartbeat.unsafeAlive().size >= config.fastQuorumSize) {
      config.roundSystem
        .nextFastRound(leaderId, round)
        .getOrElse(config.roundSystem.nextClassicRound(leaderId, round))
    } else {
      config.roundSystem.nextClassicRound(leaderId, round)
    }

    (state, leader == address) match {
      case (Inactive, true) =>
        // We are the new leader!
        logger.debug(s"Leader $address was inactive, but is now the leader.")
        round = nextRound
        sendPhase1as()
        resendPhase1asTimer.start()
        state = Phase1(mutable.Map(), mutable.Buffer(), resendPhase1asTimer)

      case (Inactive, false) =>
        // Don't do anything. We're still not the leader.
        logger.debug(s"Leader $address was inactive and still is.")

      case (Phase1(_, _, resendPhase1asTimer), true) =>
        // We were and still are the leader, but in a higher round.
        logger.debug(s"Leader $address was the leader and still is.")
        round = nextRound
        sendPhase1as()
        resendPhase1asTimer.reset()
        state = Phase1(mutable.Map(), mutable.Buffer(), resendPhase1asTimer)

      case (Phase1(_, _, resendPhase1asTimer), false) =>
        // We are no longer the leader!
        logger.debug(s"Leader $address was the leader, but no longer is.")
        resendPhase1asTimer.stop()
        state = Inactive

      case (Phase2(_, _, resendPhase2asTimer), true) =>
        // We were and still are the leader, but in a higher round.
        logger.debug(s"Leader $address was the leader and still is.")
        resendPhase2asTimer.stop()
        round = nextRound
        sendPhase1as()
        resendPhase1asTimer.start()
        state = Phase1(mutable.Map(), mutable.Buffer(), resendPhase1asTimer)

      case (Phase2(_, _, resendPhase2asTimer), false) =>
        // We are no longer the leader!
        logger.debug(s"Leader $address was the leader, but no longer is.")
        resendPhase2asTimer.stop()
        state = Inactive
    }
  }

  def executeLog(): Unit = {
    while (log.contains(chosenWatermark)) {
      log(chosenWatermark) match {
        case ECommand(Command(clientAddressBytes, clientId, command)) =>
          val clientAddress = transport.addressSerializer.fromBytes(
            clientAddressBytes.toByteArray()
          )

          // True if this command has not already been executed.
          val executed = clientTable.get(clientAddress) match {
            case Some((highestClientId, _)) => clientId <= highestClientId
            case None                       => false
          }

          if (executed) {
            return
          }

          val output = stateMachine.run(command.toByteArray())
          clientTable(clientAddress) = (clientId, output)
          // clientTable = clientTable + (clientAddress -> (clientId, output))
          val client = chan[Client[Transport]](clientAddress, Client.serializer)
          client.send(
            ClientInbound().withProposeReply(
              ProposeReply(round = round,
                           clientId = clientId,
                           result = ByteString.copyFrom(output))
            )
          )
        case ENoop => // Do nothing.
      }
      chosenWatermark += 1
    }
  }
}
