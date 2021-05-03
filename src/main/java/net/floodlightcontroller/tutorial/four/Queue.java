package net.floodlightcontroller.tutorial.four;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketQueue;
import org.projectfloodlight.openflow.protocol.OFQueueGetConfigReply;
import org.projectfloodlight.openflow.protocol.OFQueueGetConfigRequest;
import org.projectfloodlight.openflow.protocol.OFQueueStatsEntry;
import org.projectfloodlight.openflow.protocol.OFQueueStatsReply;
import org.projectfloodlight.openflow.protocol.OFQueueStatsRequest;
import org.projectfloodlight.openflow.protocol.OFQueueStatsRequest.Builder;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionEnqueue;
import org.projectfloodlight.openflow.protocol.action.OFActionSetQueue;
import org.projectfloodlight.openflow.protocol.queueprop.OFQueueProp;
import org.projectfloodlight.openflow.protocol.queueprop.OFQueuePropMaxRate;
import org.projectfloodlight.openflow.protocol.queueprop.OFQueuePropMinRate;
import org.projectfloodlight.openflow.protocol.ver13.OFQueuePropertiesSerializerVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.util.UtilLog;

public class Queue implements IFloodlightModule, IOFMessageListener {

	protected IFloodlightProviderService provider;
	protected IOFSwitchService switchService;

	protected static Logger logger;

	@Override
	public String getName() {

		return Queue.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

		/**
		 * MATCH Como o OFFactory retornado por OFFactories a partir de OFVersion então
		 * podemos fazer de forma genéria A criação de matches
		 */
		Ethernet l2 = provider.bcStore.get(cntx, provider.CONTEXT_PI_PAYLOAD);

		// Trata apenas pacotes IPV4 nesse exemplo
		if (!l2.getEtherType().equals(EthType.IPv4)) {
			return Command.CONTINUE;
		}
		Map<String, Object> camadas = new HashMap<String, Object>();
		camadas.put("L2", l2);
		if (l2.getEtherType().equals(EthType.IPv4)) {
			IPv4 l3 = (IPv4) l2.getPayload();
			camadas.put("L3", l3);
			if (l3.getProtocol().equals(IpProtocol.TCP)) {
				TCP l4 = (TCP) l3.getPayload();
				camadas.put("L4", l4);
			} else if (l3.getProtocol().equals(IpProtocol.UDP)) {
				UDP l4 = (UDP) l3.getPayload();
				camadas.put("L4", l4);
			}
		}
		// Disponível apenas para OpenFLow 1.3
		if (camadas.containsKey("L4") && sw.getOFFactory().getVersion().equals(OFVersion.OF_13)) {

			/**
			 * Pegando algumas informações das Queues do switch que gerou o packe_in
			 */
			OFQueueGetConfigRequest config = sw.getOFFactory().buildQueueGetConfigRequest().setPort(OFPort.ALL).build();// Request
																														// Queue
																														// de
																														// todas
																														// as
																														// portas

			ListenableFuture<OFQueueGetConfigReply> future = switchService.getActiveSwitch(sw.getId())
					.writeRequest(config); // Send request para o switch

			boolean doThis1 = false; // esta dando time out
			if (doThis1) {
				try {
					OFQueueGetConfigReply reply = future.get(); // TODO entender porque ocorre o time-out

					for (OFPacketQueue queue : reply.getQueues()) {
						OFPort port = queue.getPort();
						long id = queue.getQueueId();
						for (OFQueueProp prop : queue.getProperties()) {
							int rate;
							switch (prop.getType()) {
							case OFQueuePropertiesSerializerVer13.MIN_RATE_VAL:
								OFQueuePropMinRate minRate = OFQueuePropMinRate.class.cast(prop);
								rate = minRate.getRate();
								break;
							case OFQueuePropertiesSerializerVer13.MAX_RATE_VAL:
								OFQueuePropMaxRate maxRate = OFQueuePropMaxRate.class.cast(prop);
								rate = maxRate.getRate();
								break;

							default:
								break;
							}

						}

					}

				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			/**
			 * Pegando algumas estatísticas
			 */
			boolean doThis2 = false;
			if (doThis2) {
				OFQueueStatsRequest statsRequest = sw.getOFFactory().buildQueueStatsRequest().build();
				ListenableFuture<List<OFQueueStatsReply>> futureStats = sw.writeStatsRequest(statsRequest);
				try {
					List<OFQueueStatsReply> replies = futureStats.get(10, TimeUnit.SECONDS);

					for (OFQueueStatsReply reply : replies) {
						for (OFQueueStatsEntry entry : reply.getEntries()) {
							logger.info("Switch:" + sw.getId().toString() + "Queue_ID:[" + entry.getQueueId() + "]");
							entry.getPortNo();

						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			/**
			 * Adicionando flow em uma Queue
			 */
			ArrayList<OFAction> actions = new ArrayList<OFAction>();

			/* For OpenFlow 1.0 */
			if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) == 0) {
				OFActionEnqueue enqueue = sw.getOFFactory().actions().buildEnqueue().setPort(OFPort.of(2)).setQueueId(1).build();
				actions.add(enqueue);
			} else { /* For OpenFlow 1.1+ */
				OFActionSetQueue setQueue = sw.getOFFactory().actions().buildSetQueue().setQueueId(1).build();
				actions.add(setQueue);
			}

			OFFlowAdd flowAdd = sw.getOFFactory().buildFlowAdd().setActions(actions).build();

		}
		return Command.CONTINUE;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		lista.add(IOFSwitchService.class);
		return lista;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		provider = context.getServiceImpl(IFloodlightProviderService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		logger = LoggerFactory.getLogger(Queue.class);

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		provider.addOFMessageListener(OFType.PACKET_IN, this);
		logger.info(UtilLog.blue("Queue.java adicionado em listners"));

	}

}
