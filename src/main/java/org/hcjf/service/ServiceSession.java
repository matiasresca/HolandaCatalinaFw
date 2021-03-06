package org.hcjf.service;

import org.hcjf.layers.Layer;
import org.hcjf.properties.SystemProperties;
import org.hcjf.service.security.Grants;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * This class must be implemented for all implementation
 * of the service session.
 * This kind of session provides an authenticated environment for all the
 * service thread.
 * @author javaito
 */
public class ServiceSession implements Comparable {

    private static final SystemSession SYSTEM_SESSION;
    private static final ServiceSession GUEST_SESSION;

    static {
        GUEST_SESSION = new GuestSession();
        SYSTEM_SESSION = new SystemSession();
    }

    private final UUID id;
    private String sessionName;
    private final Map<Long, List<LayerStackElement>> layerStack;
    private final Map<Long, Map<String, Object>> properties;
    private final Map<Long, Long> systemTimeByThread;
    private final ThreadMXBean threadMXBean;
    private final List<ServiceSession> identities;
    private final Set<Grants.Grant> grants;
    private Locale locale;

    public ServiceSession(UUID id) {
        this.id = id;
        properties = new HashMap<>();
        layerStack = Collections.synchronizedMap(new HashMap<>());
        systemTimeByThread = new HashMap<>();
        threadMXBean = ManagementFactory.getThreadMXBean();
        locale = SystemProperties.getLocale();
        identities = new ArrayList<>();
        grants = new HashSet<>();
    }

    /**
     * Add a new identity to the service session.
     * @param serviceSession New identity.
     */
    public final void addIdentity(ServiceSession serviceSession) {
        identities.add(0, serviceSession);
    }

    /**
     * Remove the last added identity to the session.
     */
    public final void removeIdentity() {
        if(!identities.isEmpty()) {
            identities.remove(0);
        }
    }

    /**
     * Return the last identity added into the session.
     * @param <S> Expected identity type.
     * @return Service session that represents the current identity.
     */
    public final <S extends ServiceSession> S currentIdentity() {
        S result = (S) this;
        if(!identities.isEmpty()) {
            result = (S) identities.get(0);
        }
        return result;
    }

    /**
     * Return the session id.
     * @return Session id.
     */
    public final UUID getId() {
        return id;
    }

    /**
     * Set the session name.
     * @param sessionName Session name.
     */
    public final void setSessionName(String sessionName) {
        this.sessionName = sessionName;
    }

    /**
     * Return the session name.
     * @return Session Session name.
     */
    public final String getSessionName() {
        return sessionName;
    }

    /**
     * Return the properties name of the session.
     * @return Unmodifiable properties map.
     */
    public final Map<String, Object> getProperties() {
        Map<String, Object> result = null;
        if(properties.containsKey(Thread.currentThread().getId())) {
            result = Collections.unmodifiableMap(properties.get(Thread.currentThread().getId()));
        }
        return result;
    }

    /**
     * Start some thread over this session.
     */
    public final synchronized void startThread() {
        systemTimeByThread.put(Thread.currentThread().getId(),
                threadMXBean.getCurrentThreadCpuTime());
        layerStack.put(Thread.currentThread().getId(), new ArrayList<>());
        properties.put(Thread.currentThread().getId(), new HashMap<>());
        onStartThread();
    }

    /**
     * Call to hook of the starts thread.
     */
    protected void onStartThread() {}

    /**
     * End some thread over this session.
     */
    public final synchronized void endThread() {
        layerStack.remove(Thread.currentThread().getId());
        properties.remove(Thread.currentThread().getId());
        onEndThread();
        addThreadTime(threadMXBean.getCurrentThreadCpuTime() -
                systemTimeByThread.remove(Thread.currentThread().getId()));
    }

    /**
     * Call to hook of the ends thread.
     */
    protected void onEndThread(){}

    /**
     * Put all the properties over the session.
     * @param properties Properties.
     */
    public final void putAll(Map<String, Object> properties) {
        this.properties.get(Thread.currentThread().getId()).putAll(properties);
    }

    /**
     * Put a property over the session.
     * @param propertyName Property name.
     * @param propertyValue Property value.
     */
    public final void put(String propertyName, Object propertyValue) {
        properties.get(Thread.currentThread().getId()).put(propertyName, propertyValue);
    }

    /**
     * Returns a session property.
     * @param propertyName Property name.
     * @param <O> Expected return type.
     * @return Session value.
     */
    public final <O extends Object> O get(String propertyName) {
        return (O) properties.get(Thread.currentThread().getId()).get(propertyName);
    }

    /**
     * Removes a session property.
     * @param propertyName Session property name.
     * @param <O> Expected return type.
     * @return Session value removed.
     */
    public final <O extends Object> O remove(String propertyName) {
        return (O) properties.get(Thread.currentThread().getId()).remove(propertyName);
    }

    /**
     * Add an element into the layer stack.
     * @param element Layer stack element.
     */
    public final void putLayer(LayerStackElement element) {
        layerStack.get(Thread.currentThread().getId()).add(0, element);
    }

    /**
     * Removes the head of the layer stack.
     */
    public final void removeLayer() {
        layerStack.get(Thread.currentThread().getId()).remove(0);
    }

    /**
     * Returns the layer stack of the session.
     * @return Layer stack.
     */
    public final Collection<LayerStackElement> getLayerStack() {
        return Collections.unmodifiableCollection(layerStack.get(Thread.currentThread().getId()));
    }

    /**
     * Returns the first layer of the session layer stack.
     * @return Current layer.
     */
    public final LayerStackElement getCurrentLayer() {
        LayerStackElement result = null;
        if(layerStack.get(Thread.currentThread().getId()).size() > 0) {
            result = layerStack.get(Thread.currentThread().getId()).get(0);
        }
        return result;
    }

    /**
     * Returns the second layer of the session layer stack.
     * @return Invoker layer.
     */
    public final LayerStackElement getInvokerLayer() {
        LayerStackElement result = null;
        if(layerStack.get(Thread.currentThread().getId()).size() > 1) {
            result = layerStack.get(Thread.currentThread().getId()).get(1);
        }
        return result;
    }

    /**
     * Returns locale of the session.
     * @return Session locale.
     */
    public final Locale getLocale() {
        return locale;
    }

    /**
     * Set locale of the session.
     * @param locale Session locale.
     */
    public final void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     * Add system use time to specific session in nanoseconds.
     * @param time System use time in nanoseconds.
     */
    protected void addThreadTime(long time){
    }

    /**
     * Add a grant into the session.
     * @param grant Grant instance.
     */
    public final void addGrant(Grants.Grant grant) {
        grants.add(grant);
    }

    /**
     * Removes the grant of the session.
     * @param grant Grant instance.
     */
    public final void removeGrant(Grants.Grant grant) {
        grants.remove(grant);
    }

    /**
     * Returns the grants set of the session.
     * @return Grants set.
     */
    public final Set<Grants.Grant> getGrants() {
        return Collections.unmodifiableSet(grants);
    }

    public final boolean containsGrant(String grantId) {
        boolean result = false;
        for(Grants.Grant grant : grants) {
            result = grant.getPermissionId().equals(grantId);
            if(result){
                break;
            }
        }
        return result;
    }

    /**
     * Verify if the current session is the system session.
     * @return True if the current session is the system session.
     */
    public final boolean isSystemSession() {
        return equals(getSystemSession());
    }

    /**
     * Verify if the current session is the guest session.
     * @return True if the current session is the guest session.
     */
    public final boolean isGuestSession() {
        return equals(getGuestSession());
    }

    /**
     * Compare this session with other object.
     * @param object Object to compare.
     * @return Return an integer to represent the difference between this session
     * an the object.
     */
    @Override
    public final int compareTo(Object object) {
        int result = hashCode() - object.hashCode();
        if(getClass().equals(object.getClass())) {
            ServiceSession otherSession = (ServiceSession) object;
            if (getId().equals(otherSession.getId())) {
                result = 0;
            }
        }
        return result;
    }

    /**
     * Verify if the object to compare is an instance of service session and then verify if the current id is equals to
     * the id of the service session into the parameter object.
     * @param obj Other service session.
     * @return Return true if the id of both service session are equals.
     */
    @Override
    public final boolean equals(Object obj) {
        boolean result = false;
        if(obj instanceof ServiceSession) {
            result = ((ServiceSession)obj).getId().equals(getId());
        }
        return result;
    }

    /**
     * Return the instance of the system session.
     * @return System session.
     */
    public static final ServiceSession getSystemSession() {
        return SYSTEM_SESSION;
    }

    /**
     * Return the instance of the guest session.
     * @return Guest session.
     */
    public static final ServiceSession getGuestSession() {
        return GUEST_SESSION;
    }

    /**
     * This method obtain the current session from the current thread.
     * @param <S> Expected session type.
     * @return Current session.
     */
    public static final <S extends ServiceSession> S getCurrentSession() {
        Thread currentThread = Thread.currentThread();
        if(ServiceThread.class.isAssignableFrom(currentThread.getClass())) {
            return (S) ((ServiceThread)currentThread).getSession();
        } else {
            throw new IllegalStateException("The current thread is not a service thread.");
        }
    }

    /**
     * Return the current identity associated to the current thread.
     * @param <S> Expected session type.
     * @return Current identity.
     */
    public static final <S extends ServiceSession> S getCurrentIdentity() {
        Thread currentThread = Thread.currentThread();
        if(ServiceThread.class.isAssignableFrom(currentThread.getClass())) {
            return ((ServiceThread)currentThread).getSession() == null ? null :
                    (S) ((ServiceThread)currentThread).getSession().currentIdentity();
        } else {
            throw new IllegalStateException("The current thread is not a service thread.");
        }
    }

    /**
     * This session is the session used in the case of some internal system task
     * run over a service.
     */
    private static class SystemSession extends ServiceSession {

        public SystemSession() {
            super(new UUID(0,0));
            setSessionName(SystemProperties.get(SystemProperties.Service.SYSTEM_SESSION_NAME));
        }

    }

    /**
     * This class is the default session of the system.
     */
    private static class GuestSession extends ServiceSession {

        public GuestSession() {
            super(new UUID(0,1));
            setSessionName(SystemProperties.get(SystemProperties.Service.GUEST_SESSION_NAME));
        }

    }

    public static final class LayerStackElement {

        private final Class<? extends Layer> layerClass;
        private final String implName;
        private final boolean plugin;
        private final boolean stateful;

        public LayerStackElement(Class<? extends Layer> layerClass, String implName, boolean plugin, boolean stateful) {
            this.layerClass = layerClass;
            this.implName = implName;
            this.plugin = plugin;
            this.stateful = stateful;
        }

        /**
         * Returns the layer class.
         * @return Layer class.
         */
        public Class<? extends Layer> getLayerClass() {
            return layerClass;
        }

        /**
         * Returns the implementation name.
         * @return Implementation name.
         */
        public String getImplName() {
            return implName;
        }

        /**
         * Returns true if the layer is a plugin or false in the otherwise.
         * @return Plugin value.
         */
        public boolean isPlugin() {
            return plugin;
        }

        /**
         * Returns true if the layer is a stateful instance or false in the otherwise.
         * @return Stateful value.
         */
        public boolean isStateful() {
            return stateful;
        }
    }
}
