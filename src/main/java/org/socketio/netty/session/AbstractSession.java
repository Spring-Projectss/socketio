/**
 * Copyright 2012 Ronen Hamias, Anton Kharenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.socketio.netty.session;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.socketio.netty.TransportType;
import org.socketio.netty.packets.IPacket;
import org.socketio.netty.packets.Packet;
import org.socketio.netty.packets.PacketType;

public abstract class AbstractSession implements IManagedSession {
	
	private final Logger log = LoggerFactory.getLogger(getClass());
	
	private final String sessionId;
	private final String origin;
	private final TransportType transportType;
	private final SocketAddress address;
	private final ISessionDisconnectHandler disconnectHandler;
	private final int localPort;
	
	private final Packet connectPacket = new Packet(PacketType.CONNECT);
	private final Packet disconnectPacket = new Packet(PacketType.DISCONNECT);
	private final Packet heartbeatPacket = new Packet(PacketType.HEARTBEAT);
	
	protected final SocketIOHeartbeatScheduler heartbeatScheduler;
	
	private final AtomicReference<State> stateHolder = new AtomicReference<State>(State.CREATED);
	
	private final TransportType upgradedFromTransportType;
	
	private volatile boolean discarded = false;
	
	public AbstractSession (final TransportType transportType, final Channel channel, final String sessionId, final String origin, 
			final ISessionDisconnectHandler disconnectHandler, final TransportType upgradedFromTransportType, final int localPort) {
		this.sessionId = sessionId;
		this.address = channel.getRemoteAddress();
		this.origin = origin;
		this.localPort = localPort;
		this.transportType = transportType;
		this.disconnectHandler = disconnectHandler;
		this.upgradedFromTransportType = upgradedFromTransportType;

		preparePacket(connectPacket);
		preparePacket(heartbeatPacket);
		preparePacket(disconnectPacket);
		
		heartbeatScheduler = new SocketIOHeartbeatScheduler(this);
		setState(State.CONNECTING);
	}
	
	protected final void preparePacket(IPacket packet) {
		packet.setOrigin(getOrigin());
		packet.setSessionId(getSessionId());
		packet.setTransportType(transportType);
	}
	
	@Override
	public final String getSessionId() {
		return sessionId;
	}
	
	@Override
	public final boolean isUpgradedSession() {
		return upgradedFromTransportType != null;
	}
	
	@Override
	public TransportType getUpgradedFromTransportType() {
		return upgradedFromTransportType;
	}

	@Override
	public final SocketAddress getRemoteAddress() {
		return address;
	}
	
	@Override
	public final String getOrigin() {
		return origin;
	}
	
	@Override
	public boolean connect(final Channel channel) {
		heartbeatScheduler.reschedule();
		boolean connectFirstTime = stateHolder.compareAndSet(State.CONNECTING, State.CONNECTED); 
		if (connectFirstTime) {
			log.debug("Session {} state changed from {} to {}", new Object[] {getSessionId(), State.CONNECTING, State.CONNECTED});
			channel.write(connectPacket);
		}
		return connectFirstTime;
	}
	
	@Override
	public State getState() {
		return stateHolder.get();
	}
	
	@Override
	public void setState(final State state) {
		State previousState = stateHolder.getAndSet(state);
		if (previousState != state) {
			log.debug("Session {} state changed from {} to {}", new Object[] {getSessionId(), previousState, state});
		}
	}
	
	@Override
	public void disconnect() {
		if (getState() == State.DISCONNECTED) {
			return;
		}
		
		setState(State.DISCONNECTING);
		heartbeatScheduler.disableHeartbeat();
		if (!discarded) {
			send(disconnectPacket);
			disconnectHandler.onSessionDisconnect(this);
		}
		setState(State.DISCONNECTED);
	}

	@Override
	public void disconnect(final Channel channel) {
		if (getState() == State.DISCONNECTED) {
			return;
		}
		
		setState(State.DISCONNECTING); 
		heartbeatScheduler.disableHeartbeat();
		if (!discarded) {
			if (channel != null) {
				channel.write(disconnectPacket);
			}
			disconnectHandler.onSessionDisconnect(this);
		}
		setState(State.DISCONNECTED);
	}
	
	@Override
	public void sendHeartbeat() {
		send(heartbeatPacket);
	}
	
	@Override
	public void send(final String message) {
		Packet messagePacket = new Packet(PacketType.MESSAGE);
        messagePacket.setData(message);
        send(messagePacket);
	}
	
	@Override
	public void acceptPacket(final Channel channel, final Packet packet) {
	}
	
	public void discard() {
		discarded = true;
	}
	
	@Override
	public void acceptHeartbeat() {
		heartbeatScheduler.reschedule();
	}

	@Override
	public int getLocalPort() {
		return localPort;
	}
	
	
}