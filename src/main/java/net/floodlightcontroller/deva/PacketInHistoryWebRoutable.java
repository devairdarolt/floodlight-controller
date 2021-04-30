/**
 * @author devairdarolt
 * 
 * Classe que informa à API REST que estamos registrando essa API e vinculando seus URLs a um recurso específico.
 */
package net.floodlightcontroller.deva;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class PacketInHistoryWebRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
		router.attach("/history/json",PacketInHistoryResource.class);
		return router;
	}

	@Override
	public String basePath() {
		
		return "wm/pkthistory";
	}

}
