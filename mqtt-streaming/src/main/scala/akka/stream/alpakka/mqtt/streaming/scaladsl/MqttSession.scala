/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.mqtt.streaming
package scaladsl

import java.util.concurrent.atomic.AtomicLong

import akka.{NotUsed, actor => untyped}
import akka.actor.typed.scaladsl.adapter._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.alpakka.mqtt.streaming.impl._
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import akka.util.ByteString

import scala.concurrent.{Future, Promise}
import scala.util.control.NoStackTrace

object MqttSession {

  private[streaming] type CommandFlow[A] =
    Flow[Command[A], ByteString, NotUsed]
  private[streaming] type EventFlow[A] =
    Flow[ByteString, Either[MqttCodec.DecodeError, Event[A]], NotUsed]
}

/**
 * Represents MQTT session state for both clients or servers. Session
 * state can survive across connections i.e. their lifetime is
 * generally longer.
 */
abstract class MqttSession {

  /**
   * Tell the session to perform a command regardless of the state it is
   * in. This is important for sending Publish messages in particular,
   * as a connection may not have been established with a session.
   * @param cp The command to perform
   * @tparam A The type of any carry for the command.
   */
  final def tell[A](cp: Command[A]): Unit =
    this ! cp

  /**
   * Tell the session to perform a command regardless of the state it is
   * in. This is important for sending Publish messages in particular,
   * as a connection may not have been established with a session.
   * @param cp The command to perform
   * @tparam A The type of any carry for the command.
   */
  def ![A](cp: Command[A]): Unit

  /**
   * Shutdown the session gracefully
   */
  def shutdown(): Unit
}

/**
 * Represents client-only sessions
 */
abstract class MqttClientSession extends MqttSession {
  import MqttSession._

  /**
   * @return a flow for commands to be sent to the session
   */
  private[streaming] def commandFlow[A]: CommandFlow[A]

  /**
   * @return a flow for events to be emitted by the session
   */
  private[streaming] def eventFlow[A]: EventFlow[A]
}

object ActorMqttClientSession {
  def apply(settings: MqttSessionSettings)(implicit mat: Materializer,
                                           system: untyped.ActorSystem): ActorMqttClientSession =
    new ActorMqttClientSession(settings)

  /**
   * A PINGREQ failed to receive a PINGRESP - the connection must close
   *
   * http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
   * 3.1.2.10 Keep Alive
   */
  case object PingFailed extends Exception with NoStackTrace

  private[scaladsl] val clientSessionCounter = new AtomicLong
}

/**
 * Provides an actor implementation of a client session
 * @param settings session settings
 */
final class ActorMqttClientSession(settings: MqttSessionSettings)(implicit mat: Materializer,
                                                                  system: untyped.ActorSystem)
    extends MqttClientSession {

  import ActorMqttClientSession._

  private val clientSessionId = clientSessionCounter.getAndIncrement()
  private val consumerPacketRouter =
    system.spawn(RemotePacketRouter[Consumer.Event], "client-consumer-packet-id-allocator-" + clientSessionId)
  private val producerPacketRouter =
    system.spawn(LocalPacketRouter[Producer.Event], "client-producer-packet-id-allocator-" + clientSessionId)
  private val subscriberPacketRouter =
    system.spawn(LocalPacketRouter[Subscriber.Event], "client-subscriber-packet-id-allocator-" + clientSessionId)
  private val unsubscriberPacketRouter =
    system.spawn(LocalPacketRouter[Unsubscriber.Event], "client-unsubscriber-packet-id-allocator-" + clientSessionId)
  private val clientConnector =
    system.spawn(
      ClientConnector(consumerPacketRouter,
                      producerPacketRouter,
                      subscriberPacketRouter,
                      unsubscriberPacketRouter,
                      settings),
      "client-connector-" + clientSessionId
    )

  import MqttCodec._
  import MqttSession._

  import system.dispatcher

  override def ![A](cp: Command[A]): Unit = cp match {
    case Command(cp: Publish, carry) =>
      clientConnector ! ClientConnector.PublishReceivedLocally(cp, carry)
    case c: Command[_] => throw new IllegalStateException(c + " is not a client command that can be sent directly")
  }

  override def shutdown(): Unit = {
    system.stop(clientConnector.toUntyped)
    system.stop(consumerPacketRouter.toUntyped)
    system.stop(producerPacketRouter.toUntyped)
    system.stop(subscriberPacketRouter.toUntyped)
    system.stop(unsubscriberPacketRouter.toUntyped)
  }

  private val pingReqBytes = PingReq.encode(ByteString.newBuilder).result()

  override def commandFlow[A]: CommandFlow[A] =
    Flow[Command[_]]
      .watch(clientConnector.toUntyped)
      .watchTermination() {
        case (_, terminated) =>
          terminated.foreach(_ => clientConnector ! ClientConnector.ConnectionLost)
          NotUsed
      }
      .flatMapMerge(
        settings.commandParallelism, {
          case Command(cp: Connect, carry) =>
            val reply = Promise[Source[ClientConnector.ForwardConnectCommand, NotUsed]]
            clientConnector ! ClientConnector.ConnectReceivedLocally(cp, carry, reply)
            Source.fromFutureSource(
              reply.future.map(_.map {
                case ClientConnector.ForwardConnect => cp.encode(ByteString.newBuilder).result()
                case ClientConnector.ForwardPingReq => pingReqBytes
                case ClientConnector.ForwardPublish(publish, packetId) =>
                  publish.encode(ByteString.newBuilder, packetId).result()
                case ClientConnector.ForwardPubRel(packetId) =>
                  PubRel(packetId).encode(ByteString.newBuilder).result()
              }.mapError {
                case ClientConnector.PingFailed => ActorMqttClientSession.PingFailed
              })
            )
          case Command(cp: PubAck, _) =>
            val reply = Promise[Consumer.ForwardPubAck.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubAckReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: PubRec, _) =>
            val reply = Promise[Consumer.ForwardPubRec.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubRecReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: PubComp, _) =>
            val reply = Promise[Consumer.ForwardPubComp.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubCompReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: Subscribe, carry) =>
            val reply = Promise[Subscriber.ForwardSubscribe]
            clientConnector ! ClientConnector.SubscribeReceivedLocally(cp, carry, reply)
            Source.fromFuture(reply.future.map(command => cp.encode(ByteString.newBuilder, command.packetId).result()))
          case Command(cp: Unsubscribe, carry) =>
            val reply = Promise[Unsubscriber.ForwardUnsubscribe]
            clientConnector ! ClientConnector.UnsubscribeReceivedLocally(cp, carry, reply)
            Source.fromFuture(reply.future.map(command => cp.encode(ByteString.newBuilder, command.packetId).result()))
          case Command(cp: Disconnect.type, _) =>
            val reply = Promise[ClientConnector.ForwardDisconnect.type]
            clientConnector ! ClientConnector.DisconnectReceivedLocally(reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case c: Command[_] => throw new IllegalStateException(c + " is not a client command")
        }
      )

  override def eventFlow[A]: EventFlow[A] =
    Flow[ByteString]
      .watch(clientConnector.toUntyped)
      .watchTermination() {
        case (_, terminated) =>
          terminated.foreach(_ => clientConnector ! ClientConnector.ConnectionLost)
          NotUsed
      }
      .via(new MqttFrameStage(settings.maxPacketSize))
      .map(_.iterator.decodeControlPacket(settings.maxPacketSize))
      .mapAsync(settings.eventParallelism) {
        case Right(cp: ConnAck) =>
          val reply = Promise[ClientConnector.ForwardConnAck]
          clientConnector ! ClientConnector.ConnAckReceivedFromRemote(cp, reply)
          reply.future.map {
            case ClientConnector.ForwardConnAck(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: SubAck) =>
          val reply = Promise[Subscriber.ForwardSubAck]
          subscriberPacketRouter ! LocalPacketRouter.Route(cp.packetId,
                                                           Subscriber.SubAckReceivedFromRemote(reply),
                                                           reply)
          reply.future.map {
            case Subscriber.ForwardSubAck(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: UnsubAck) =>
          val reply = Promise[Unsubscriber.ForwardUnsubAck]
          unsubscriberPacketRouter ! LocalPacketRouter.Route(cp.packetId,
                                                             Unsubscriber.UnsubAckReceivedFromRemote(reply),
                                                             reply)
          reply.future.map {
            case Unsubscriber.ForwardUnsubAck(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: Publish) =>
          val reply = Promise[Consumer.ForwardPublish.type]
          clientConnector ! ClientConnector.PublishReceivedFromRemote(cp, reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: PubAck) =>
          val reply = Promise[Producer.ForwardPubAck]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubAckReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubAck(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: PubRec) =>
          val reply = Promise[Producer.ForwardPubRec]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubRecReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubRec(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: PubRel) =>
          val reply = Promise[Consumer.ForwardPubRel.type]
          consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubRelReceivedFromRemote(reply), reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: PubComp) =>
          val reply = Promise[Producer.ForwardPubComp]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubCompReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubComp(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(PingResp) =>
          val reply = Promise[ClientConnector.ForwardPingResp.type]
          clientConnector ! ClientConnector.PingRespReceivedFromRemote(reply)
          reply.future.map(_ => Right(Event(PingResp)))
        case Right(cp) => Future.failed(new IllegalStateException(cp + " is not a client event"))
        case Left(de) => Future.successful(Left(de))
      }
}

object MqttServerSession {

  /**
   * Used to signal that a client session has ended
   */
  final case class ClientSessionTerminated(clientId: String)
}

/**
 * Represents server-only sessions
 */
abstract class MqttServerSession extends MqttSession {
  import MqttSession._
  import MqttServerSession._

  /**
   * Used to observe client connections being terminated
   */
  def watchClientSessions: Source[ClientSessionTerminated, NotUsed]

  /**
   * @return a flow for commands to be sent to the session in relation to a connection id
   */
  private[streaming] def commandFlow[A](connectionId: ByteString): CommandFlow[A]

  /**
   * @return a flow for events to be emitted by the session in relation t a connection id
   */
  private[streaming] def eventFlow[A](connectionId: ByteString): EventFlow[A]
}

object ActorMqttServerSession {
  def apply(settings: MqttSessionSettings)(implicit mat: Materializer,
                                           system: untyped.ActorSystem): ActorMqttServerSession =
    new ActorMqttServerSession(settings)

  /**
   * A PINGREQ was not received within the required keep alive period - the connection must close
   *
   * http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
   * 3.1.2.10 Keep Alive
   */
  case object PingFailed extends Exception with NoStackTrace

  private[scaladsl] val serverSessionCounter = new AtomicLong
}

/**
 * Provides an actor implementation of a server session
 * @param settings session settings
 */
final class ActorMqttServerSession(settings: MqttSessionSettings)(implicit mat: Materializer,
                                                                  system: untyped.ActorSystem)
    extends MqttServerSession {

  import MqttServerSession._
  import ActorMqttServerSession._

  private val serverSessionId = serverSessionCounter.getAndIncrement()

  private val (terminations, terminationsSource) = Source
    .queue[ServerConnector.ClientSessionTerminated](settings.clientTerminationWatcherBufferSize,
                                                    OverflowStrategy.dropNew)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()

  def watchClientSessions: Source[ClientSessionTerminated, NotUsed] =
    terminationsSource.map {
      case ServerConnector.ClientSessionTerminated(clientId) => ClientSessionTerminated(clientId)
    }

  private val consumerPacketRouter =
    system.spawn(RemotePacketRouter[Consumer.Event], "server-consumer-packet-id-allocator-" + serverSessionId)
  private val producerPacketRouter =
    system.spawn(LocalPacketRouter[Producer.Event], "server-producer-packet-id-allocator-" + serverSessionId)
  private val publisherPacketRouter =
    system.spawn(RemotePacketRouter[Publisher.Event], "server-publisher-packet-id-allocator-" + serverSessionId)
  private val unpublisherPacketRouter =
    system.spawn(RemotePacketRouter[Unpublisher.Event], "server-unpublisher-packet-id-allocator-" + serverSessionId)
  private val serverConnector =
    system.spawn(
      ServerConnector(terminations,
                      consumerPacketRouter,
                      producerPacketRouter,
                      publisherPacketRouter,
                      unpublisherPacketRouter,
                      settings),
      "server-connector-" + serverSessionId
    )

  import MqttCodec._
  import MqttSession._

  import system.dispatcher

  override def ![A](cp: Command[A]): Unit = cp match {
    case Command(cp: Publish, carry) =>
      serverConnector ! ServerConnector.PublishReceivedLocally(cp, carry)
    case c: Command[_] => throw new IllegalStateException(c + " is not a server command that can be sent directly")
  }

  override def shutdown(): Unit = {
    system.stop(serverConnector.toUntyped)
    system.stop(consumerPacketRouter.toUntyped)
    system.stop(producerPacketRouter.toUntyped)
    system.stop(publisherPacketRouter.toUntyped)
    system.stop(unpublisherPacketRouter.toUntyped)
    terminations.complete()
  }

  private val pingRespBytes = PingResp.encode(ByteString.newBuilder).result()

  override def commandFlow[A](connectionId: ByteString): CommandFlow[A] =
    Flow[Command[_]]
      .watch(serverConnector.toUntyped)
      .watchTermination() {
        case (_, terminated) =>
          terminated.foreach(_ => serverConnector ! ServerConnector.ConnectionLost(connectionId))
          NotUsed
      }
      .flatMapMerge(
        settings.commandParallelism, {
          case Command(cp: ConnAck, _) =>
            val reply = Promise[Source[ClientConnection.ForwardConnAckCommand, NotUsed]]
            serverConnector ! ServerConnector.ConnAckReceivedLocally(connectionId, cp, reply)
            Source.fromFutureSource(
              reply.future.map(_.map {
                case ClientConnection.ForwardConnAck =>
                  cp.encode(ByteString.newBuilder).result()
                case ClientConnection.ForwardPingResp =>
                  pingRespBytes
                case ClientConnection.ForwardPublish(publish, packetId) =>
                  publish.encode(ByteString.newBuilder, packetId).result()
                case ClientConnection.ForwardPubRel(packetId) =>
                  PubRel(packetId).encode(ByteString.newBuilder).result()
              }.mapError {
                case ServerConnector.PingFailed => ActorMqttServerSession.PingFailed
              })
            )
          case Command(cp: SubAck, _) =>
            val reply = Promise[Publisher.ForwardSubAck.type]
            publisherPacketRouter ! RemotePacketRouter.Route(cp.packetId, Publisher.SubAckReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: UnsubAck, _) =>
            val reply = Promise[Unpublisher.ForwardUnsubAck.type]
            unpublisherPacketRouter ! RemotePacketRouter.Route(cp.packetId,
                                                               Unpublisher.UnsubAckReceivedLocally(reply),
                                                               reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: PubAck, _) =>
            val reply = Promise[Consumer.ForwardPubAck.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubAckReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: PubRec, _) =>
            val reply = Promise[Consumer.ForwardPubRec.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubRecReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case Command(cp: PubComp, _) =>
            val reply = Promise[Consumer.ForwardPubComp.type]
            consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubCompReceivedLocally(reply), reply)
            Source.fromFuture(reply.future.map(_ => cp.encode(ByteString.newBuilder).result()))
          case c: Command[_] => throw new IllegalStateException(c + " is not a server command")
        }
      )

  override def eventFlow[A](connectionId: ByteString): EventFlow[A] =
    Flow[ByteString]
      .watch(serverConnector.toUntyped)
      .watchTermination() {
        case (_, terminated) =>
          terminated.foreach(_ => serverConnector ! ServerConnector.ConnectionLost(connectionId))
          NotUsed
      }
      .via(new MqttFrameStage(settings.maxPacketSize))
      .map(_.iterator.decodeControlPacket(settings.maxPacketSize))
      .mapAsync(settings.eventParallelism) {
        case Right(cp: Connect) =>
          val reply = Promise[ClientConnection.ForwardConnect.type]
          serverConnector ! ServerConnector.ConnectReceivedFromRemote(connectionId, cp, reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: Subscribe) =>
          val reply = Promise[Publisher.ForwardSubscribe.type]
          serverConnector ! ServerConnector.SubscribeReceivedFromRemote(connectionId, cp, reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: Unsubscribe) =>
          val reply = Promise[Unpublisher.ForwardUnsubscribe.type]
          serverConnector ! ServerConnector.UnsubscribeReceivedFromRemote(connectionId, cp, reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: Publish) =>
          val reply = Promise[Consumer.ForwardPublish.type]
          serverConnector ! ServerConnector.PublishReceivedFromRemote(connectionId, cp, reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: PubAck) =>
          val reply = Promise[Producer.ForwardPubAck]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubAckReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubAck(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: PubRec) =>
          val reply = Promise[Producer.ForwardPubRec]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubRecReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubRec(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(cp: PubRel) =>
          val reply = Promise[Consumer.ForwardPubRel.type]
          consumerPacketRouter ! RemotePacketRouter.Route(cp.packetId, Consumer.PubRelReceivedFromRemote(reply), reply)
          reply.future.map(_ => Right(Event(cp)))
        case Right(cp: PubComp) =>
          val reply = Promise[Producer.ForwardPubComp]
          producerPacketRouter ! LocalPacketRouter.Route(cp.packetId, Producer.PubCompReceivedFromRemote(reply), reply)
          reply.future.map {
            case Producer.ForwardPubComp(carry: Option[A] @unchecked) => Right(Event(cp, carry))
          }
        case Right(PingReq) =>
          val reply = Promise[ClientConnection.ForwardPingReq.type]
          serverConnector ! ServerConnector.PingReqReceivedFromRemote(connectionId, reply)
          reply.future.map(_ => Right(Event(PingReq)))
        case Right(Disconnect) =>
          val reply = Promise[ClientConnection.ForwardDisconnect.type]
          serverConnector ! ServerConnector.DisconnectReceivedFromRemote(connectionId, reply)
          reply.future.map(_ => Right(Event(Disconnect)))
        case Right(cp) => Future.failed(new IllegalStateException(cp + " is not a server event"))
        case Left(de) => Future.successful(Left(de))
      }
}
