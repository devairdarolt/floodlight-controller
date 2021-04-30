/**
 * @author devairdarolt
 * 
 * Interface de serviço que deve ser fornecido por {@link IPacketInHystoryService}
 */
package net.floodlightcontroller.deva;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.SwitchMessagePair;
import net.floodlightcontroller.util.ConcurrentCircularBuffer;

public interface IPacketInHystoryService extends IFloodlightService {
	public ConcurrentCircularBuffer<SwitchMessagePair> getBuffer();

}
