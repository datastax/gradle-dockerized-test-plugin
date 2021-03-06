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

import org.gradle.api.*
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.api.internal.tasks.testing.detection.*
import org.gradle.internal.remote.ConnectionAcceptor
import org.gradle.internal.remote.MessagingServer
import org.gradle.internal.remote.ObjectConnection
import org.gradle.internal.remote.internal.ConnectCompletion
import org.gradle.internal.remote.internal.IncomingConnector
import org.apache.commons.lang3.SystemUtils
import org.apache.maven.artifact.versioning.ComparableVersion
import org.gradle.internal.remote.internal.hub.MessageHubBackedObjectConnection
import org.gradle.process.internal.JavaExecHandleFactory
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory
import org.gradle.process.internal.ExecHandleFactory

import javax.inject.Inject

class DockerizedTestPlugin implements Plugin<Project> {

    def currentUser
    def messagingServer
    def workerSemaphore = new DefaultWorkerSemaphore()

    @Inject
    DockerizedTestPlugin(MessagingServer messagingServer) {
        this.currentUser = SystemUtils.IS_OS_WINDOWS ? "0" : "id -u".execute().text.trim()
        this.messagingServer = new MessageServer(messagingServer.connector, messagingServer.executorFactory)
    }

    void configureTest(project, test) {
        def ext = test.extensions.create("docker", DockerizedTestExtension, [] as Object[])
        def startParameter = project.gradle.startParameter
        ext.volumes = [ "$startParameter.gradleUserHomeDir": "$startParameter.gradleUserHomeDir",
                        "$project.projectDir":"$project.projectDir"]
        ext.user = currentUser
        test.doFirst {
            def extension = test.extensions.docker
            if (extension?.image) {
                test.testExecuter = new DefaultTestExecuter(newProcessBuilderFactory(project, extension, test.processBuilderFactory), actorFactory, moduleRegistry);
            }
        }
    }

    void apply(Project project) {

        boolean preGradle2_12 = new ComparableVersion(project.gradle.gradleVersion).compareTo(new ComparableVersion('2.14')) < 0
        if (preGradle2_12) throw new GradleException("dockerized-test plugin requires Gradle 2.14+")

        project.tasks.withType(Test).each { test -> configureTest(project, test) }
        project.tasks.whenTaskAdded { task ->
            if (task instanceof Test) configureTest(project, task)
        }
        workerSemaphore.applyTo(project)
    }

    def newProcessBuilderFactory(project, extension, defaultProcessBuilderFactory) {

        def execHandleFactory = [newJavaExec: { -> new DockerizedJavaExecHandleBuilder(extension, project.fileResolver, workerSemaphore)}] as JavaExecHandleFactory
        new DefaultWorkerProcessFactory(defaultProcessBuilderFactory.workerLogLevel,
                                        messagingServer,
                                        defaultProcessBuilderFactory.workerFactory.classPathRegistry,
                                        defaultProcessBuilderFactory.idGenerator,
                                        defaultProcessBuilderFactory.gradleUserHomeDir,
                                        defaultProcessBuilderFactory.workerFactory.temporaryFileProvider,
                                        execHandleFactory
                                        )
    }

    class MessageServer implements MessagingServer {
        def IncomingConnector connector;
        def ExecutorFactory executorFactory;

        public MessageServer(IncomingConnector connector, ExecutorFactory executorFactory) {
            this.connector = connector;
            this.executorFactory = executorFactory;
        }

        public ConnectionAcceptor accept(Action<ObjectConnection> action) {
            return this.connector.accept(new ConnectEventAction(action, executorFactory), true);
        }


    }

    class ConnectEventAction implements Action<ConnectCompletion> {
        def action;
        def executorFactory;

        public ConnectEventAction(Action<ObjectConnection> action, executorFactory) {
            this.executorFactory = executorFactory
            this.action = action
        }

        public void execute(ConnectCompletion completion) {
            action.execute(new MessageHubBackedObjectConnection(executorFactory, completion));
        }
    }

}