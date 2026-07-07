pipeline {
    agent any

    // Names must match "Manage Jenkins > Tools" on your controller.
    tools {
        maven 'maven-3.9'
        jdk 'jdk-17'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        AWS_REGION     = 'us-east-1'
        ECR_REPO_NAME  = 'ecs-fargate-appconfig-demo'
        IMAGE_TAG      = "${env.BUILD_NUMBER}"
        // Jenkins credential of kind "AWS Credentials" (requires the AWS Steps plugin),
        // or run the agent on an EC2 instance profile and remove the withAWS wrappers.
        AWS_CREDENTIALS_ID = 'aws-deploy-credentials'
    }

    stages {

        stage('Build & Unit Tests (100% coverage gate)') {
            steps {
                // jacoco:check bound to the verify phase fails the build below 100% line/branch coverage
                sh 'mvn -B clean verify'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                    publishHTML(target: [
                        reportDir: 'app/target/site/jacoco',
                        reportFiles: 'index.html',
                        reportName: 'JaCoCo Coverage (app)',
                        allowMissing: true,
                        keepAll: true,
                        alwaysLinkToLastBuild: true
                    ])
                }
            }
        }

        stage('Sonar Analysis & Quality Gate') {
            steps {
                // 'sonarqube' must match the server name in "Manage Jenkins > System > SonarQube servers"
                // (for SonarCloud: URL https://sonarcloud.io + a token credential).
                // sonar.qualitygate.wait makes the scanner itself block on the gate and fail the
                // build if it is red - no webhook back to Jenkins required.
                withSonarQubeEnv('sonarqube') {
                    timeout(time: 10, unit: 'MINUTES') {
                        sh 'mvn -B sonar:sonar -Dsonar.qualitygate.wait=true'
                    }
                }
            }
        }

        stage('Deploy Platform Stacks (CDK)') {
            steps {
                withAWS(credentials: env.AWS_CREDENTIALS_ID, region: env.AWS_REGION) {
                    dir('infra') {
                        sh 'npx cdk deploy DemoEcrStack DemoAppConfigStack --require-approval never'
                    }
                }
            }
        }

        stage('Image Build & Push to ECR (Jib, no Docker needed)') {
            steps {
                withAWS(credentials: env.AWS_CREDENTIALS_ID, region: env.AWS_REGION) {
                    sh '''
                        ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
                        ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO_NAME}"
                        ECR_PASSWORD=$(aws ecr get-login-password --region "${AWS_REGION}")

                        mvn -B -pl app jib:build \
                            -Djib.to.image="${ECR_URI}:${IMAGE_TAG}" \
                            -Djib.to.tags=latest \
                            -Djib.to.auth.username=AWS \
                            -Djib.to.auth.password="${ECR_PASSWORD}"
                    '''
                }
            }
        }

        stage('Deploy Fargate Service (CDK)') {
            steps {
                withAWS(credentials: env.AWS_CREDENTIALS_ID, region: env.AWS_REGION) {
                    dir('infra') {
                        sh "npx cdk deploy DemoFargateServiceStack -c imageTag=${IMAGE_TAG} --require-approval never"
                    }
                }
            }
        }

        stage('Smoke Test') {
            steps {
                withAWS(credentials: env.AWS_CREDENTIALS_ID, region: env.AWS_REGION) {
                    sh '''
                        SERVICE_URL=$(aws cloudformation describe-stacks \
                            --stack-name DemoFargateServiceStack \
                            --query "Stacks[0].Outputs[?contains(OutputKey, 'ServiceURL')].OutputValue | [0]" \
                            --output text)
                        echo "Service URL: ${SERVICE_URL}"
                        for i in $(seq 1 30); do
                            if curl -sf "${SERVICE_URL}/api/config" > /dev/null; then
                                echo "Configuration endpoint is healthy."
                                curl -s "${SERVICE_URL}/api/config"
                                exit 0
                            fi
                            echo "Waiting for service... (${i}/30)"
                            sleep 10
                        done
                        echo "Service did not become healthy in time." >&2
                        exit 1
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Deployed image tag ${IMAGE_TAG} to ECS Fargate."
        }
        failure {
            echo 'Pipeline failed - check the stage logs above.'
        }
    }
}
