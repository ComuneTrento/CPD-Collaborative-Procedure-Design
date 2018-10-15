package it.beng.modeler.microservice.services;

import io.vertx.core.Vertx;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class BridgeEventService {

    private static final Log logger = LogFactory.getLog(BridgeEventService.class);

    private static final Map<Class<? extends BridgeEventService>, BridgeEventService> SERVICES = new LinkedHashMap<>();

    protected final Vertx vertx;

    public BridgeEventService(Vertx vertx) {
        if (SERVICES.get(this.getClass()) != null) {
            throw new IllegalStateException("only one instance of " + this.getClass().getName() + " is allowed");
        }
        this.vertx = vertx;
        SERVICES.put(this.getClass(), this);
        init();
    }

    public static void registerService(Class<DiagramActionService> bridgeEventServiceClass) {
        SERVICES.put(bridgeEventServiceClass, null);
    }

    public static final void start(Vertx vertx) {
        for (Class<? extends BridgeEventService> serviceClass : SERVICES.keySet()) {
            try {
                serviceClass.getDeclaredConstructor(Vertx.class).newInstance(vertx);
            } catch (Exception e) {
                logger.error("could not instantiate " + serviceClass.getName()
                    + " because of " + e.getLocalizedMessage());
            }
        }
    }

    public static Collection<BridgeEventService> services() {
        return SERVICES.values();
    }

    protected abstract void init();

    public abstract Collection<PermittedOptions> inboundPermitted();

    public abstract Collection<PermittedOptions> outboundPermitted();

    public abstract boolean handle(BridgeEvent event);

}
