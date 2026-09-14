/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */
pipeline {

    environment {
        productName = "cmv"
        moduleName = "benchmark-runner"
        imageTag = "${DOCKER_ARTIFACT_REGISTRY}/${productName}-${moduleName}:${env.GIT_COMMIT}"
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
                        recordCoverage(tools: [[parser: 'JACOCO']])
                        recordIssues aggregatingResults: true, tools: [errorProne(), java()]
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
                    sh "./mvnw spring-boot:build-image-no-fork -Dspring-boot.build-image.imageName=${imageTag}"
                }
            }
            when { branch 'main' }
        }
    }
}