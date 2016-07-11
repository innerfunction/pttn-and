// Copyright 2016 InnerFunction Ltd.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
package com.innerfunction.pttn;

import android.content.Context;
import android.util.Log;

import com.innerfunction.uri.Resource;
import com.innerfunction.uri.StandardURIHandler;
import com.innerfunction.util.TypeConversions;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A container for named objects and services.
 * Acts as an object factory and IOC container. Objects built by this class are
 * instantiated and configured using an object definition read from a JSON configuration.
 * The object's properties may be configured using other built objects, or using references
 * to named objects contained by the container.
 *
 * Created by juliangoacher on 28/03/16.
 */
public class Container implements ConfigurationData, Service, MessageReceiver, MessageRouter {

    static final String Tag = Container.class.getSimpleName();

    /**
     * An Android context object.
     */
    protected Context androidContext;
    /**
     * The container's URI handler.
     */
    protected StandardURIHandler uriHandler;
    /**
     * Object for handling conversions to different value representations.
     */
    protected TypeConversions typeConversions;
    /**
     * The parent container of a nested container.
     */
    private Container parentContainer;
    /**
     * A list of names which should be built before the rest of the container's configuration is
     * processed. Names should be listed in priority order.
     */
    private List<String> priorityNames = new ArrayList<>();
    /**
     * A map of named objects.
     */
    protected Map<String,Object> nameds;
    /**
     * A list of contained services.
     */
    private List<Service> services;
    /**
     * Map of standard type names onto platform specific class names.
     */
    private Configuration types;
    /**
     * The container's configuration.
     */
    private Configuration containerConfig;
    /**
     * The container's configurable properties.
     */
    private Map<String,Property> containerProperties;
    /**
     * A map of pending object names (i.e. objects in the process of being configured) mapped onto
     * a list of pending value references (i.e. property value references to other pending objects,
     * which are caused by circular dependency cycles and which can't be fully resolved until the
     * referenced value has been fully built).
     * Used to detect dependency cycles when building the named object graph.
     * @see PendingNamed
     */
    private Map<String,List<PendingNamed>> pendingNames;
    /**
     * A map of pending property value reference counts, keyed by the property's parent object.
     * Used to manage deferred calls to the IOCContainerAware.afterConfig() method.
     */
    private Map<Object,Integer> pendingValueRefCounts;
    /**
     * A map of pending value object configurations. These are the configurations for the parent
     * objects of pending property values. These are needed for deferred calls to the
     * IFIOCContainerAware.afterIOCConfig() method.
     */
    private Map<Object,Configuration> pendingValueObjectConfigs;
    /**
     * Flag indicating whether the container and all its services are running.
     */
    private boolean running;
    /** An object configurer for the container. */
    private ObjectConfigurer containerConfigurer;

    public Container(Context context, StandardURIHandler uriHandler) {
        this.androidContext = context;
        this.uriHandler = uriHandler;
        this.typeConversions = TypeConversions.instanceForContext( context );
        this.nameds = new HashMap<>();
        this.services = new ArrayList<>();
        this.types = makeConfiguration( new HashMap<String,Object>() );
        this.running = false;
        this.containerProperties = Property.getPropertiesForObject( this );
        this.pendingNames = new HashMap<>();
        this.pendingValueRefCounts = new HashMap<>();
        this.pendingValueObjectConfigs = new HashMap<>();
        this.containerConfigurer = new ObjectConfigurer( this );
    }

    /** Set the type map. */
    public void setTypes(Configuration types) {
        this.types = (types == null) ? this.types : types;
    }

    /** Add additional type name mappings to the type map. */
    public void addTypes(Object types) {
        if( types != null ) {
            Configuration typeConfig = null;
            if( types instanceof Configuration ) {
                typeConfig = (Configuration)types;
            }
            else if( types instanceof Map ) {
                typeConfig = makeConfiguration( types );
            }
            if( typeConfig != null ) {
                this.types = this.types.mixinConfiguration( typeConfig );
            }
        }
    }

    public void setPriorityNames(String... priorityNames) {
        this.priorityNames = Arrays.asList( priorityNames );
    }

    /**
     * Create a configuration object from the specified configuration data source.
     */
    public Configuration makeConfiguration(Object config) {
        if( config instanceof Resource ) {
            Resource resource = (Resource)config;
            return new Configuration( resource.asJSONData(), resource.getURIHandler(), androidContext );
        }
        return new Configuration( config, uriHandler, androidContext );
    }

    /**
     * Instantiate and configure an object using the specified configuration.
     * @param configuration A configuration describing the object to build.
     * @param identifier    An identifier (e.g. the configuration's key path) used identify the
     *                      object in logs.
     * @return The instantiated and fully configured object.
     */
    public Object buildObject(Configuration configuration, String identifier) {
        Object object = null;
        if( configuration.hasValue("*factory") ) {
            // The configuration specifies an object factory, so resolve the factory object and
            // attempt using it to instantiate the object.
            Object factory = configuration.getValue("*factory");
            if( factory instanceof IOCObjectFactory ) {
                object = ((IOCObjectFactory)factory).buildObject( configuration, this, identifier );
                doPostInstantiation( object );
                doPostConfiguration( object );
            }
            else {
                Log.e( Tag, String.format("Building %s, invalid factory class '%s'",
                    identifier, factory.getClass() ) );
            }
        }
        else {
            // Try instantiating object from type or class info.
            object = instantiateObjectWithConfiguration( configuration, identifier );
            if( object != null ) {
                // Configure the resolved object.
                configureObject( object, configuration, identifier );
            }
        }
        return object;
    }

    /**
     * Instantiate an object from the specified configuration.
     * Use class or type info in a configuration to instantiate a new object.
     * @param configuration A configuration with instantiation hints that can be used to create an
     *                      object instance.
     * @param identifier    An identifier (e.g. the configuration's key path) used identify the
     *                      object in logs.
     * @return A newly instantiated object.
     */
    public Object instantiateObjectWithConfiguration(Configuration configuration, String identifier) {
        Object object = null;
        String className = configuration.getValueAsString( "*and-class" );
        if( className == null ) {
            String type = configuration.getValueAsString("*type");
            if( type != null ) {
                className = types.getValueAsString( type );
                if( className == null ) {
                    Log.e( Tag, String.format("Instantiating %s, no class name found for type %s",
                        identifier, type ) );
                }
            }
            else {
                Log.e( Tag, String.format("Instantiating %s, Component configuration missing *type or *and-class property",
                    identifier ) );
            }
        }
        if( className != null ) {
            object = newInstanceForClassNameAndConfiguration( className, configuration );
        }
        return object;
    }

    /**
     * Instantiate an instance of the named type.
     * Looks for a classname in the set of registered types, and then returns the result of calling
     * the newInstanceForClassNameAndConfiguration() method.
     */
    public Object newInstanceForTypeNameAndConfiguration(String typeName, Configuration configuration) {
        String className = types.getValueAsString( typeName );
        if( className == null ) {
            Log.e( Tag, String.format( "No classname found for type %s", typeName ) );
            return null;
        }
        return newInstanceForClassNameAndConfiguration( className, configuration );
    }

    /**
     * Instantiate an instance of the named class.
     * @return Returns a new instance of the class, unless a configuration proxy is registered for
     * the class name in which case a new instance of the proxy class is returned.
     */
    public Object newInstanceForClassNameAndConfiguration(String className, Configuration configuration) {
        // If config proxy available for classname then instantiate proxy instead of new instance.
        IOCProxyLookup.Entry proxyEntry = IOCProxyLookup.lookupConfigurationProxyForClassName( className );
        if( proxyEntry != null ) {
            return proxyEntry.instantiateProxy();
        }
        // Otherwise continue with class instantiation.
        try {
            Class objClass = Class.forName( className );
            Object instance = null;
            // Try constructing with a single android Context argument.
            instance = constructWithArgument( objClass, androidContext );
            // Try constructing with a single Configuration argument.
            if( instance == null ) {
                instance = constructWithArgument( objClass, configuration );
            }
            // Try constructing with a no-args constructor.
            if( instance == null ) {
                instance = objClass.newInstance();
            }
            doPostInstantiation( instance );
            return instance;
        }
        catch(ClassNotFoundException e) {
            Log.e( Tag, String.format("Class not found: %s", className ) );
        }
        catch(Exception e) {
            Log.e( Tag, String.format("Error initializing object of class %s", className ), e );
        }
        return null;
    }

    /**
     * Try instantiating an object by calling a specific constructor.
     * Looks for a single argument constructor on the specified class, accepting an argument
     * the same type as the 'arg' parameter.
     * @param objClass  The class being instantiated.
     * @param arg       A constructor method argument.
     * @return The newly constructed object, or null if the class doesn't have a matching
     * constructor.
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    private Object constructWithArgument(Class objClass, Object arg) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        try {
            Class argClass = arg.getClass();
            Constructor constructor = objClass.getConstructor( argClass );
            if( constructor != null ) {
                return constructor.newInstance( arg );
            }
        }
        catch(NoSuchMethodException nsme) {
            // Ignore: Classes not required to have a specific constructor.
        }
        return null;
    }

    /**
     * Configure an object using the specified configuration.
     * @param object        The object to configure.
     * @param configuration The object's configuration.
     * @param identifier    An identifier (e.g. the configuration's key path) used identify the
     *                      object in logs.
     */
    public void configureObject(Object object, Configuration configuration, String identifier) {
        ObjectConfigurer configurer = new ObjectConfigurer( object, this, identifier );
        configurer.configureWith( configuration );
    }

    /**
     * Configure the container with the specified configuration.
     * The container performs implicit dependency ordering. This means that if an object A has a
     * dependency on another object B, then B will be built (instantiated & configured) before A.
     * This will work for an arbitrary length dependency chain (e.g. A -> B -> C -> etc.). Implicit
     * dependency ordering relies on the fact that dependencies like this can only be specified
     * using the named: URI scheme, which uses the container's getNamed: method to resolve named
     * objects.
     * The configuration process works as follows:
     * <ul>
     * <li>
     *      This method iterates over each named object configuration and builds each object in
     *      turn.
     * </li>
     * <li>
     *      If any named object has a dependency on another named object then this will be resolved
     *      via the named:
     * </li>
     * <li>
     *      URI scheme and the container's getNamed() method.
     * </li>
     * <li>
     *      In the getNamed() method, if a name isn't found but a configuration exists then the
     *      container will attempt to build and return the named object. This means that in effect,
     *      building of an object is temporarily suspended whilst building of its dependency is
     *      prioritized. This process will recurse until the full dependency chain is resolved.
     * </li>
     * <li>
     *      The container maintains a map of names being built. This allows the container to detect
     *      dependency cycles and so avoid infinite regression. Dependency cycles are resolved, but
     *      the final object in a cycle won't be fully configured when injected into the dependent.
     * </li>
     * </ul>
     */
    public void configureWith(Configuration configuration) {
        containerConfig = configuration;
        // Build priority names first.
        for( String name : priorityNames ) {
            if( !nameds.containsKey( name ) ) {
                buildNamedObject( name );
            }
        }
        // Iterate over named object configs and build each object.
        List<String> names = configuration.getValueNames();
        for( String name : names ) {
            // Build the object only if it has not already been built and added to named.
            // (Objects which are dependencies of other objects may be configured via getNamed()
            // before this loop has iterated around to them; or as priority names).
            if( !nameds.containsKey( name ) ) {
                buildNamedObject( name );
            }
        }
    }

    /**
     * Build a named object from the available configuration and property type info.
     */
    protected Object buildNamedObject(String name) {
        // Track that we're about to build this name.
        pendingNames.put( name, new ArrayList<PendingNamed>() );
        // Build the object.
        Object object = containerConfigurer.configureProperty( name, containerConfig );
        if( object != null ) {
            // Map the named object.
            nameds.put( name, object );
        }
        // Object is configured, notify any pending named references
        List<PendingNamed> pendings = pendingNames.get( name );
        for( PendingNamed pending : pendings ) {
            if( pending.hasWaitingConfigurer() ) {
                Object value = pending.completeWithValue( object );
                // Decrement the number of pending value refs for the property object.
                Object objectKey = pending.getObjectKey();
                int refCount = pendingValueRefCounts.get( objectKey ) - 1;
                if( refCount > 0 ) {
                    pendingValueRefCounts.put( objectKey, refCount );
                }
                else {
                    pendingValueRefCounts.remove( objectKey );
                    // The property object is now fully configured, invoke its afterConfigure()
                    // method if it implements IOCContainerAware.
                    Object pendingObj = pending.getObject();
                    if( pendingObj instanceof IOCContainerAware ) {
                        Configuration objConfig = pendingValueObjectConfigs.remove( objectKey );
                        ((IOCContainerAware)pendingObj).afterIOCConfigure( objConfig );
                    }
                }
            }
        }
        // Finished building the current name, remove from list.
        pendingNames.remove( name );
        // Return the configured object.
        return object;
    }

    /** Get a named component. */
    public Object getNamed(String name) {
        Object named = nameds.get( name );
        // If named object not found then consider whether to try building it.
        if( named == null ) {
            // Check for a dependency cycle. If the requested name exists in pendingNames then the
            // named object is currently being configured.
            List<PendingNamed> pendings = pendingNames.get( name );
            if( pendings != null ) {
                Log.d( Tag, String.format("IDO: Named dependency cycle detected, creating pending entry for %s...",
                    name ) );
                // Create a placeholder object and record in the list of placeholders waiting for
                // the named configuration to complete. Note that the placeholder is returned in
                // place of the named - code above detects the placeholder and ensures that the
                // correct value is resolved instead.
                PendingNamed pending = new PendingNamed();
                pendings.add( pending );
                named = pending;
            }
            else if( containerConfig.hasValue( name ) ) {
                // The container config contains a configuration for the wanted name, but named
                // doesn't contain any reference so therefore it's likely that the object hasn't
                // been built yet; try building it now.
                named = buildNamedObject( name );
            }
        }
        // If the required name can't be resolved by this container, and it this container is a
        // nested container (and so has a parent) then ask the parent container to resolve the name.
        if( named == null && parentContainer != null ) {
            named = parentContainer.getNamed( name );
        }
        return named;
    }

    /** Configure the container with the specified data. */
    public void configureWithData(Object data) {
        Configuration configuration = new Configuration( data, uriHandler, androidContext );
        configureWith( configuration );
    }

    /** Perform standard post-instantiation operations on a new object instance. */
    public void doPostInstantiation(Object object) {
        // If the new instance if context aware then inject a reference to the android context.
        if( object instanceof IOCContextAware ) {
            ((IOCContextAware)object).setAndroidContext( androidContext );
        }
        // If the new instance is container aware then pass reference to this container.
        if( object instanceof IOCContainerAware ) {
            ((IOCContainerAware)object).setIOCContainer( this );
        }
        // If the new instance is a nested container then set its parent reference.
        if( object instanceof Container ) {
            ((Container)object).parentContainer = this;
        }
        // If instance is a service then add to list of services.
        if( object instanceof Service ) {
            services.add( (Service)object );
        }
    }

    /** Perform standard post-configuration operations on a new object instance. */
    public void doPostConfiguration(Object object) {
        // If running and the object is a service instance then start the service now that it is
        // fully configured.
        if( running && object instanceof Service ) {
            ((Service)object).startService();
        }
    }

    /** Increment the number of pending value refs for an object. */
    public void incPendingValueRefCountForPendingObject(PendingNamed pending) {
        Object objectKey = pending.getObjectKey();
        Integer refCount = pendingValueRefCounts.get( objectKey );
        if( refCount != null ) {
            pendingValueRefCounts.put( objectKey, refCount + 1 );
        }
        else {
            pendingValueRefCounts.put( objectKey, 0 );
        }
    }

    /** Test whether an object has pending value references. */
    public boolean hasPendingValueRefsForObjectKey(Object objectKey) {
        return pendingValueRefCounts.get( objectKey ) != null;
    }

    /**
     * Record the configuration for an object with pending value references.
     * Needed to ensure the the [IFIOCContainerAware afterConfiguration:] method is called correctly.
     */
    public void recordPendingValueObjectConfiguration(Object objectKey, Configuration configuration) {
        pendingValueObjectConfigs.put( objectKey, configuration );
    }

    // Service interface
    @Override
    public void startService() {
        running = true;
        for( Service service : services ) {
            try {
                service.startService();
            }
            catch(Exception e) {
                Log.e( Tag, "Starting service", e );
            }
        }
    }

    @Override
    public void stopService() {
        for( Service service : services ) {
            try {
                service.stopService();
            }
            catch(Exception e) {
                Log.e( Tag, "Stopping service", e );
            }
        }
        running = false;
    }

    // Configuration data interface
    @Override
    public Object getValue(String keyPath, String representation) {
        Object value = getNamed( keyPath );
        if( value != null && !"bare".equals( representation ) ) {
            value = typeConversions.asRepresentation( value, representation );
        }
        return value;
    }

    // MessageRouter interface
    @Override
    public boolean routeMessage(Message message, Object sender) {
        boolean routed = false;
        if( message.hasEmptyTarget() ) {
            // Message is targeted at this object.
            routed = receiveMessage( message, sender );
        }
        else {
            // Look-up the message target in named objects.
            String targetHead = message.targetHead();
            Object target = nameds.get( targetHead );
            if( target != null ) {
                message = message.popTargetHead();
                // If we have the intended target, and the target is a message handler, then let it
                // handle the message.
                if( message.hasEmptyTarget() ) {
                    if( target instanceof MessageReceiver ) {
                        routed = ((MessageReceiver)target).receiveMessage( message, sender );
                    }
                }
                else if( target instanceof MessageRouter ) {
                    // Let the current target dispatch the message to its intended target.
                    routed = ((MessageRouter)target).routeMessage( message, sender );
                }
            }
        }
        return routed;
    }

    // MessageReceiver interface
    @Override
    public boolean receiveMessage(Message message, Object sender) {
        return false;
    }

}