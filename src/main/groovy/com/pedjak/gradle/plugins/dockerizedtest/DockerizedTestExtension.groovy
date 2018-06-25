/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedjak.gradle.plugins.dockerizedtest

import org.gradle.api.JavaVersion

class DockerizedTestExtension {

    String image
    Map volumes
    String user
    JavaVersion javaVersion
    Integer connectTimeout

    /**
     * @param CreateContainerCmd createCmd
     * @param DockerClient client
     */
    Closure beforeContainerCreate

    /**
     * @param String containerId
     * @param DockerClient client
     */
    Closure afterContainerCreate

    /**
     * @param String containerId
     * @param DockerClient client
     */
    Closure beforeContainerStart

    /**
     * @param String containerId
     * @param DockerClient client
     */
    Closure afterContainerStart

    /**
     * (no params)
     */
    Closure afterContainerStop = { containerId, client ->
        try
        {
            client.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            // ignore any error
        }
    }

    def client
}
