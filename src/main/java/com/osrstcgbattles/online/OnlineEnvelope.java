package com.osrstcgbattles.online;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Bounded, versioned envelope used for every online WebSocket message. */
public final class OnlineEnvelope
{
	public static final int PROTOCOL_VERSION = 3;
	public static final int MAX_REQUEST_ID_LENGTH = 64;

	private final int protocolVersion;
	private final String requestId;
	private final OnlineMessageType type;
	private final JsonObject payload;

	public OnlineEnvelope(String requestId, OnlineMessageType type, JsonObject payload)
	{
		this(PROTOCOL_VERSION, requestId, type, payload);
	}

	public OnlineEnvelope(int protocolVersion, String requestId, OnlineMessageType type, JsonObject payload)
	{
		if (protocolVersion != PROTOCOL_VERSION) throw new IllegalArgumentException("unsupported protocol version");
		if (requestId == null || requestId.isEmpty() || requestId.length() > MAX_REQUEST_ID_LENGTH)
			throw new IllegalArgumentException("invalid request ID");
		this.protocolVersion = protocolVersion;
		this.requestId = requestId;
		this.type = Objects.requireNonNull(type, "type");
		this.payload = payload == null ? new JsonObject() : payload.deepCopy();
	}

	public int getProtocolVersion() { return protocolVersion; }
	public String getRequestId() { return requestId; }
	public OnlineMessageType getType() { return type; }
	public JsonObject getPayload() { return payload == null ? new JsonObject() : payload.deepCopy(); }

	public void validate()
	{
		if (protocolVersion != PROTOCOL_VERSION) throw new IllegalArgumentException("unsupported protocol version");
		if (requestId == null || requestId.isEmpty() || requestId.length() > MAX_REQUEST_ID_LENGTH)
			throw new IllegalArgumentException("invalid request ID");
		if (type == null) throw new IllegalArgumentException("message type is required");
	}
}
