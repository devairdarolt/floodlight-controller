/**
 * @author devairdarolt
 * 
 * Classe que lida com uma solicitação REST e retorna um json com os serviços fornecidos por {@link IPacketInHystoryService}
 */
package net.floodlightcontroller.deva;


import net.floodlightcontroller.core.types.SwitchMessagePair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

public class PacketInHistoryResource extends ServerResource{
	
	@Get("json")//Já transforma List em vetor json
	public List<SwitchMessagePair> retrieve(){
		//TODO: Verificar se da pra usar .class como key
		IPacketInHystoryService pihr = (IPacketInHystoryService) getContext().getAttributes().get(IPacketInHystoryService.class.getCanonicalName());
		ArrayList<SwitchMessagePair> lista = new ArrayList<SwitchMessagePair>();
		lista.addAll(Arrays.asList(pihr.getBuffer().snapshot())); // 1. [pihr.getBuffer().snapshot())] - retorna um array    2. [Arrays.asList()] - Converte array em ArrayList
		return lista;
	}

}
