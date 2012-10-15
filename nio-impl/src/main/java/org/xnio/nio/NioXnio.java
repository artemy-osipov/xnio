/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xnio.nio;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.jboss.logging.Logger;
import org.xnio.IoUtils;
import org.xnio.Version;
import org.xnio.Xnio;
import org.xnio.OptionMap;
import org.xnio.XnioWorker;

/**
 * An NIO-based XNIO provider for a standalone application.
 */
final class NioXnio extends Xnio {

    private static final Logger log = Logger.getLogger("org.xnio.nio");

    private interface SelectorCreator {
        Selector open() throws IOException;
    }

    private final SelectorCreator selectorCreator;

    static final boolean NIO2;

    static {
        log.info("XNIO NIO Implementation Version " + Version.VERSION);
        boolean nio2 = false;
        try {
            // try to find an NIO.2 interface on the system class path
            Class.forName("java.nio.channels.MulticastChannel", false, null);
            log.trace("NIO.2 detected");
            nio2 = true;
        } catch (Throwable t) {
        }
        NIO2 = nio2;
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                final String bugLevel = System.getProperty("sun.nio.ch.bugLevel");
                if (bugLevel == null) System.setProperty("sun.nio.ch.bugLevel", "");
                return null;
            }
        });
    }

    /**
     * Construct a new NIO-based XNIO provider instance.  Should only be invoked by the service loader.
     */
    NioXnio() {
        super("nio");
        final String providerClassName = SelectorProvider.provider().getClass().getCanonicalName();
        if ("sun.nio.ch.PollSelectorProvider".equals(providerClassName)) {
            log.warnf("The currently defined selector provider class (%s) is not supported for use with XNIO", providerClassName);
        }
        log.tracef("Starting up with selector provider %s", providerClassName);
        selectorCreator = AccessController.doPrivileged(
            new PrivilegedAction<SelectorCreator>() {
                public SelectorCreator run() {
                    try {
                        // A Polling selector is most efficient on most platforms for one-off selectors.  Try to hack a way to get them on demand.
                        final Class<? extends Selector> selectorImplClass = Class.forName("sun.nio.ch.PollSelectorImpl").asSubclass(Selector.class);
                        final Constructor<? extends Selector> constructor = selectorImplClass.getDeclaredConstructor(SelectorProvider.class);
                        // Usually package private.  So untrusting.
                        constructor.setAccessible(true);
                        log.trace("Using polling selector type for temporary selectors.");
                        return new SelectorCreator() {
                            public Selector open() throws IOException {
                                try {
                                    return constructor.newInstance(SelectorProvider.provider());
                                } catch (InstantiationException e) {
                                    return Selector.open();
                                } catch (IllegalAccessException e) {
                                    return Selector.open();
                                } catch (InvocationTargetException e) {
                                    try {
                                        throw e.getTargetException();
                                    } catch (IOException e2) {
                                        throw e2;
                                    } catch (RuntimeException e2) {
                                        throw e2;
                                    } catch (Error e2) {
                                        throw e2;
                                    } catch (Throwable t) {
                                        throw new IllegalStateException("Unexpected invocation exception", t);
                                    }
                                }
                            }
                        };
                    } catch (Exception e) {
                        // ignore.
                    }
                    // Can't get our selector type?  That's OK, just use the default.
                    log.trace("Using default selector type for temporary selectors.");
                    return new SelectorCreator() {
                        public Selector open() throws IOException {
                            return Selector.open();
                        }
                    };
                }
            }
        );
    }

    public XnioWorker createWorker(final ThreadGroup threadGroup, final OptionMap optionMap, final Runnable terminationTask) throws IOException, IllegalArgumentException {
        final NioXnioWorker worker = new NioXnioWorker(this, threadGroup, optionMap, terminationTask);
        worker.start();
        return worker;
    }

    private final ThreadLocal<Selector> selectorThreadLocal = new ThreadLocal<Selector>() {
        public void remove() {
            // if no selector was created, none will be closed
            IoUtils.safeClose(get());
            super.remove();
        }
    };

    Selector getSelector() throws IOException {
        final ThreadLocal<Selector> threadLocal = selectorThreadLocal;
        Selector selector = threadLocal.get();
        if (selector == null) {
            selector = selectorCreator.open();
            threadLocal.set(selector);
        }
        return selector;
    }
}
