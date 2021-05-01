/**
 * @author devairdarolt
 * 
 * O objetivo dessa classe é processar os packet-in, verificar endereços macs e ips de src e destinos
 * verificar os header dos pacotes
 */

package net.floodlightcontroller.tutorial.one;

import java.util.Collection;

import java.util.Map;


import org.fusesource.jansi.Ansi;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.VlanVid;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

//Outras dependencias
import net.floodlightcontroller.core.IFloodlightProviderService;
import java.util.ArrayList;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessPacketIn implements IOFMessageListener, IFloodlightModule {

	

	protected IFloodlightProviderService floodlightProviderService;
	protected Set<String> listaMACs;
	protected static Logger logger;
	protected Ansi ansi; //Variável utilizada para imprimir formatado no terminal

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// IFloodlightService lista = {}
		//Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		//lista.add(IFloodlightService.class);
		return null;
	}

	/**
	 * Agora precisamos conectá-lo ao sistema de carregamento do módulo. Dizemos ao
	 * carregador de módulo que dependemos de IFloodlightPrividerService, modificando a função * getModuleDependencies ().
	 * 
	 * Toda variável que contém *Service declarada na main precisa informar sua classe nessa lista
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// IFloodlightService lista = {}
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		return lista;

	}

	/**
	 * Inicializa as variáveis da nossa classe... Init é chamado no início do
	 * processo de inicialização do controlador. Ele é executado principalmente para
	 * carregar dependências e inicializar estruturas de dados.
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		listaMACs = new ConcurrentSkipListSet<String>();
		logger = LoggerFactory.getLogger(ProcessPacketIn.class);
		ansi = new Ansi();
	}

	/**
	 * Tratamento da mensagem de entrada de PACKET_IN. Agora é hora de implementar o
	 * listner básico. Vamos registrar as mensagens PACKET_IN em nosso método de
	 * inicialização. Nesse método temos a garantia de que outros módulos dos quais
	 * dependemos já foram inicializados.
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);

		ansi = new Ansi();
		ansi.fgYellow();
		ansi.a("Listner adicionado para packet_in");
		ansi.reset();
		logger.info(ansi.toString());
		// agora essa classe será avisado toda vez que um PACKET_IN chegar ao
		// controlador

	}

	/**
	 * Também precisamos inserir um ID para nosso listner. Isso é feito na chamada
	 * getName ().
	 */
	@Override
	public String getName() {
		return ProcessPacketIn.class.getSimpleName();
	}

	/**
	 * Agora temos que definir o comportamento que queremos para as mensagens
	 * PACKET_IN. Observe que retornamos Command.CONTINUE para permitir que esta
	 * mensagem continue a ser tratada por outros manipuladores PACKET_IN também.
	 * 
	 * @param IOFSwitch sw - switch que gerou o packet in
	 * @param OFMessage msg
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			// bcStore é uma lista com todos os pacotes PACKET_IN desserializados
			// FloodlightContextStore<Ethernet> lista = IFloodlightProviderService.bcStore;
			// v
			Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
			ansi = new Ansi().fgBrightGreen().a("[PACKET_IN]" + sw.getId().toString() + " ").fgDefault();
			
			
			MacAddress mac = eth.getDestinationMACAddress();
			VlanVid vlanId = VlanVid.ofVlan(eth.getVlanID());
			if (eth.getEtherType().equals(EthType.IPv4)) {
				IPv4 ipv4 = (IPv4) eth.getPayload();
				byte[] ipOptions = ipv4.getOptions();
				IPv4Address dstIpv4 = ipv4.getSourceAddress();
				ansi.fgBrightGreen().a("[dstIPv4 "+ipv4.getDestinationAddress().toString()+"] ").fgDefault();
				// verifica se o ipvq possui carga/payload
				if (ipv4.getProtocol().equals(IpProtocol.TCP)) {
					TCP tcp = (TCP) ipv4.getPayload();
					TransportPort srcPort = tcp.getSourcePort();
					TransportPort dstPort = tcp.getDestinationPort();
					ansi.fgBrightGreen().a(" [TCPport:"+dstPort.toString()+"]").fgDefault();
					short flags = tcp.getFlags();
					// TODO lógica para utilizar o TCP/IPV4/MAC --> Exemplos shortestPath, roudRobin
				} else if (ipv4.getProtocol().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ipv4.getPayload();
					TransportPort srcPort = udp.getSourcePort();
					TransportPort dstPort = udp.getDestinationPort();
					ansi.fgBrightGreen().a(" [UDPport:"+dstPort.toString()+"]").fgDefault();
					// TODO lógica para tratar fluxos UDP aqui. UDP/IPV4/MAC ---> Exemplo caminho
					// menos prioritário
				}
			} else if (eth.getEtherType().equals(EthType.ARP)) {
				ARP arp = (ARP) eth.getPayload();
				boolean gratuitous = arp.isGratuitous();

			} else {

			}
			break;

		default:
			break;
		}
		//logger.info(ansi.toString());
		return Command.CONTINUE; // Comando para que a menssagem constinue sendo processada por outros listners
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
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

}
