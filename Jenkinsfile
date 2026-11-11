/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */
pipeline {

    environment {
        productName = "cmv"
        moduleName = "benchmark-runner"
        IMAGE_TAG = "${DOCKER_ARTIFACT_REGISTRY}/${productName}-${moduleName}:${env.GIT_COMMIT}"
    }

    agent {
        label 'jnlp-himem'
    }

    stages {
        // Building on main
        stage('Pull SDK Docker Image') {
            agent {
                docker {
                    image 'eclipse-temurin:25'
                    reuseNode true
                }
            }
            environment {
                HOME = "${WORKSPACE_TMP}"
            }
            stages {
                stage('Build Project') {
                    steps {
                        withMaven {
                            sh "./mvnw clean verify"
                        }
                    }
                }
                stage('Record Issues') {
                    steps {
                        discoverGitReferenceBuild()
                        recordIssues aggregatingResults: true, tools: [java()]
                    }
                }
                stage('Run Sonar Scan') {
                    steps {
                        withSonarQubeEnv('cessda-sonar') {
                            withMaven {
                                sh "./mvnw sonar:sonar"
                            }
                        }
                    }
                    when { branch 'main' }
                }
            }
        }
        stage("Get Sonar Quality Gate") {
            steps {
                timeout(time: 1, unit: 'HOURS') {
                    waitForQualityGate abortPipeline: false
                }
            }
            when { branch 'main' }
        }
        stage('Build Docker Image') {
            steps {
                withMaven {
                    sh "./mvnw spring-boot:build-image-no-fork -Dspring-boot.build-image.imageName=${IMAGE_TAG}"
                }
            }
        }
        stage('Push Docker Image') {
            steps {
                sh "gcloud auth configure-docker ${ARTIFACT_REGISTRY_HOST}"
                sh "docker push ${IMAGE_TAG}"
                sh "gcloud artifacts docker tags add ${IMAGE_TAG} ${DOCKER_ARTIFACT_REGISTRY}/${productName}-${moduleName}:latest"
            }
            when { branch 'main' }
        }
        stage('Deploy Benchmark Runner') {
            steps {
                build job: 'cessda.cmv.deploy/main', parameters: [string(name: 'benchmarkRunnerImageTag', value: env.GIT_COMMIT)], wait: false
            }
            when { branch 'main' }           
        }
    }
}
