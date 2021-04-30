package net.floodlightcontroller.deva;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.LogFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.SwitchMessagePair;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.ConcurrentCircularBuffer;
import net.floodlightcontroller.util.UtilLog;

public class PacketInHistory implements IFloodlightModule, IOFMessageListener, IPacketInHystoryService{
	//Dependencias
	protected IFloodlightProviderService floodlightProviderService;
	protected ConcurrentCircularBuffer<SwitchMessagePair> bufferCircular; //Buffer Criado para armazenar todas as msg packet-in
	protected Logger logger;
	//Referencia ao REST API
	protected IRestApiService restApi;
	
	@Override
	public String getName() {
	
		return PacketInHistory.class.getSimpleName();
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
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IPacketInHystoryService.class);
		return lista;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> map = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		map.put(PacketInHistory.class, this);
		return map;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>>  lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		lista.add(IRestApiService.class);
		return lista;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		bufferCircular = new ConcurrentCircularBuffer<SwitchMessagePair>(SwitchMessagePair.class, 100);
		logger = LoggerFactory.getLogger(PacketInHistory.class.getSimpleName());
		restApi = context.getServiceImpl(IRestApiService.class);

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		//Adiciona a classe que vincula os serviços as URLs
		restApi.addRestletRoutable(new PacketInHistoryWebRoutable());
	}
	
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			bufferCircular.add(new SwitchMessagePair(sw, msg));
			logger.info(UtilLog.blue("Criado par sw["+sw.getId().toString()+"]-msg["+msg.getVersion()+"]"));
			break;

		default:
			break;
		}
		
		return Command.CONTINUE;
	}
	/**
	 * Implementa a classe IPktInService
	 */
	@Override
	public ConcurrentCircularBuffer<SwitchMessagePair> getBuffer() {		
		return this.bufferCircular;
	}
}
