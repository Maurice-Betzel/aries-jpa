/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.jpa.container.impl;

import java.util.Dictionary;

import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceProvider;
import javax.sql.DataSource;

import org.apache.aries.jpa.container.parser.impl.PersistenceUnit;
import org.apache.aries.jpa.container.weaving.impl.DummyDataSource;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.jpa.EntityManagerFactoryBuilder;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks matching persistence providers for a persistence unit.
 * If a provider is found:
 * - an EntityManagerFactoryBuilder is installed
 * - A DataSourceTracker is installed if the JtaDataSource refers to an OSGi service 
 */
public class PersistenceProviderTracker extends ServiceTracker<PersistenceProvider, StoredPerProvider> {
    private static final String JAVAX_PERSISTENCE_PROVIDER = "javax.persistence.provider";

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistenceProviderTracker.class);

    private PersistenceUnit punit;

    public PersistenceProviderTracker(BundleContext containerContext, PersistenceUnit punit) {
        super(containerContext, createFilter(containerContext, punit), null);
        this.punit = punit;
    }

    private static Filter createFilter(BundleContext context, PersistenceUnit punit) {
        String filter;
        if (punit.getPersistenceProviderClassName() != null) {
            filter = String.format("(&(objectClass=%s)(%s=%s))",
                                   PersistenceProvider.class.getName(),
                                   JAVAX_PERSISTENCE_PROVIDER,
                                   punit.getPersistenceProviderClassName());
        } else {
            filter = String.format("(objectClass=%s)", PersistenceProvider.class.getName());
        }

        try {
            return context.createFilter(filter);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public StoredPerProvider addingService(ServiceReference<PersistenceProvider> reference) {
        String providerName = (String)reference.getProperty(JAVAX_PERSISTENCE_PROVIDER);
        // FIXME should be set when creating the EMF was successful
        if (punit.getPersistenceProviderClassName() == null) {
            punit.setProviderClassName(providerName);
        }
        StoredPerProvider stored = new StoredPerProvider();
        LOGGER.info("Found provider for " + punit.getPersistenceUnitName() + " " + punit.getPersistenceProviderClassName());
        
        PersistenceProvider provider = context.getService(reference);

        createAndCloseDummyEMF(provider);

        stored.builder = new AriesEntityManagerFactoryBuilder(context, provider, reference.getBundle(), punit);
        Dictionary<String, ?> props = AriesEntityManagerFactoryBuilder.createBuilderProperties(punit, punit.getBundle());
        stored.reg = context.registerService(EntityManagerFactoryBuilder.class, stored.builder , props);
        return stored;
    }

    /**
     * Create and close a dummy EMF to give the PersistenceProvider a chance to call
     * punit.addTransformer(). This has to occur as early as possible as weaving needs
     * to be done before the first entity class is loaded. So we can not wait till the
     * real DataSource is found.
     */
    private void createAndCloseDummyEMF(PersistenceProvider provider) {
        DataSource dummyDataSource = new DummyDataSource();
        punit.setJtaDataSource(dummyDataSource);
        punit.setNonJtaDataSource(dummyDataSource);
        try {
            EntityManagerFactory emf = provider.createContainerEntityManagerFactory(punit, null);
            emf.close();
        } catch (Exception e) {
            LOGGER.debug("Error while creating the Dummy EntityManagerFactory to allow weaving.", e);
        }
        punit.setJtaDataSource(null);
        punit.setNonJtaDataSource(null);
    }

    @Override
    public void removedService(ServiceReference<PersistenceProvider> reference, StoredPerProvider stored) {
        LOGGER.info("Lost provider for " + punit.getPersistenceUnitName() + " " + punit.getPersistenceProviderClassName());
        try {
        	stored.reg.unregister();
        } catch (Exception e) {
        	LOGGER.debug("An exception occurred unregistering a persistence unit {}", stored.builder.getPUName());
        }
        if (stored.builder != null) {
            stored.builder.close();
        }
        super.removedService(reference, stored);
    }
}
