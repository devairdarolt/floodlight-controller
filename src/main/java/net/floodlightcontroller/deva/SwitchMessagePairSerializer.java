/**
 * @author devairdarolt
 * 
 * JsonSerializer para personalizar quis os campos do  {@link IOFSwitch} e {@link OFMessage} 
 * deve ser serializado pelo {@link JsonSerialize}
 */

package net.floodlightcontroller.deva;

import java.io.IOException;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketInReason;
import org.projectfloodlight.openflow.protocol.OFType;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import net.floodlightcontroller.core.types.SwitchMessagePair;
import net.floodlightcontroller.util.OFMessageUtils;

public class SwitchMessagePairSerializer extends JsonSerializer<SwitchMessagePair> {

	@Override
	public void serialize(SwitchMessagePair smPair, JsonGenerator jGen, SerializerProvider sProvider)
			throws IOException, JsonProcessingException {

		/** Para escrever um marcador inicial de um valor JSONobject */
		jGen.writeStartObject();

		jGen.writeFieldName("message");

		// OFPacketIn id = (OFPacketIn)smPair.getMessage()).getBufferId().getInt();
		OFMessage m = smPair.getMessage();
		jGen.writeStartObject();
		{
			/** Para evitar otros tipos de msg que não sejam PACKET_IN */
			if (m.getType().equals(OFType.PACKET_IN)) {
				OFPacketIn packetIn = (OFPacketIn) m;

				jGen.writeNumberField("bufferId", packetIn.getBufferId().getInt());
				// 1. OUTRA FORMA -- OFMessageUtils.getInPort(packetIn).getPortNumber();
				jGen.writeNumberField("inPort", packetIn.getInPort().getPortNumber());
				jGen.writeNumberField("packetDataLength", packetIn.getData().length);
				jGen.writeBinaryField("packetData", packetIn.getData());
				jGen.writeStringField("reason", packetIn.getReason().toString());
				jGen.writeNumberField("lenght", packetIn.getTotalLen());
			}
			jGen.writeStringField("typer", m.getType().toString());
			jGen.writeStringField("version", m.getVersion().toString());
			jGen.writeNumberField("xid", m.getXid());

			jGen.writeString("switch");
			{
				jGen.writeStartObject();
				jGen.writeStringField("dpid", smPair.getSwitch().getId().toString());
			}
			jGen.writeEndObject();
		}
		jGen.writeEndObject();

	}

}
