/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.remote.internal.inet.InetAddressFactory;

public class GradleBridge
{
    private static final Logger LOGGER = Logging.getLogger(GradleBridge.class);

    public static void checkBindAddress(InetAddressFactory inetAddressFactory)
    {
        try
        {
            // Need to do some dirty tricks to let the Gradle daemon listen on "anyaddr".
            // Gradle 6.0 changed the default listen address from "anyaddr" to "localhost", so we have to actually
            // make it "unsafe" here. We could actually let it listen on the Docker gateway IP (the host's IP of
            // Docker network), but this may have unforeseen consequences.
            // I.e. client.inspectContainerCmd(containerId).exec().getNetworkSettings().getGateway()
            Field fLocalBindingAddress = InetAddressFactory.class.getDeclaredField("localBindingAddress");
            fLocalBindingAddress.setAccessible(true);
            InetAddress current = (InetAddress) fLocalBindingAddress.get(inetAddressFactory);
            if (current == null || !current.isAnyLocalAddress())
            {
                InetAddress updateTo = (new InetSocketAddress(0)).getAddress();
                fLocalBindingAddress.set(inetAddressFactory, updateTo);
                LOGGER.lifecycle("Updating InetAddressFactory.localBindingAddress from {} to {}",
                                 current, updateTo);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to check/update InetAddressFactory.localBindingAddress to 'anyaddr'", e);
        }
    }
}
