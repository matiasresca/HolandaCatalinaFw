package org.hcjf.names;

import org.hcjf.log.Log;
import org.hcjf.service.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Naming service class.
 * @author javaito
 */
public final class Naming extends Service<NamingConsumer> {

    public static final String NAMING_LOG_TAG = "NAMING";
    private static final String NAME = "NamingService";

    private static final Naming instace;

    static {
        instace = new Naming();
    }

    private final Map<String, NamingConsumer> consumers;

    /**
     * Service constructor.
     */
    private Naming() {
        super(NAME, 1);
        consumers = new HashMap<>();
    }

    /**
     * This method register the consumer in the service.
     * @param consumer Object with the logic to consume the service.
     * @throws RuntimeException It contains exceptions generated by
     *                          the particular logic of each implementation.
     */
    @Override
    public void registerConsumer(NamingConsumer consumer) {
        consumers.put(consumer.getName(), consumer);
    }

    /**
     * Unregister the service consumer.
     * @param consumer Consumer to unregister.
     */
    @Override
    public void unregisterConsumer(NamingConsumer consumer) {
        consumers.remove(consumer.getName());
    }

    /**
     * Add a new consumer into the service.
     * @param consumer Naming consumer.
     */
    public static void addNamingConsumer(NamingConsumer consumer) {
        instace.registerConsumer(consumer);
    }

    /**
     * Normalize the value using the specific naming implementation.
     * @param implName Implementation name.
     * @param value Value to normalize.
     * @return Normalized value.
     */
    public static String normalize(String implName, String value) {
        String result = value;

        if(instace.consumers.containsKey(implName)) {
            result = instace.consumers.get(implName).normalize(value);
        } else {
            Log.w(NAMING_LOG_TAG, "Naming implementation not found: %s", implName);
        }

        return result;
    }
}
