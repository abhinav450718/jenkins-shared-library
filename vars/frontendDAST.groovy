def call(Map config) {

    def TARGET_URL  = config.targetUrl  ?: "http://98.80.140.40"
    def ZAP_PORT    = config.zapPort    ?: "9000"
    def ZAP_API_KEY = config.zapApiKey  ?: "frontendkey"
    def REPORT_DIR  = config.reportDir  ?: "zap-reports"
    def ZAP_DIR     = config.zapDir     ?: "zap"
    def SLACK_CHANNEL = config.slackChannel ?: "#ci-operation-notifications"

    node {

        try {

            stage('Clean Workspace') {
                echo "Cleaning workspace..."
                deleteDir()
            }

            stage('Verify Frontend Accessibility') {
                sh """
                    HTTP_CODE=\$(curl -o /dev/null -s -w "%{http_code}" --max-time 10 ${TARGET_URL}/ || echo "000")
                    if [ "\$HTTP_CODE" = "000" ]; then
                        echo "ERROR: Frontend is NOT accessible at ${TARGET_URL}"
                        exit 1
                    else
                    fi
                """
            }

            stage('Setup Java 17') {
                echo "Downloading Java 17..."
                sh '''
                    mkdir -p java
                    cd java
                    wget -q https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                    tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                    rm -f OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                '''
            }

            stage('Download OWASP ZAP') {
                sh """
                    mkdir -p ${ZAP_DIR}
                    cd ${ZAP_DIR}
                    wget -q https://github.com/zaproxy/zaproxy/releases/download/v2.17.0/ZAP_2.17.0_Linux.tar.gz
                    tar -xzf ZAP_2.17.0_Linux.tar.gz
                    rm -f ZAP_2.17.0_Linux.tar.gz
                """
            }

            stage('Start ZAP') {
                sh """
                    JAVA_DIR=\$(ls -d java/jdk-17* | head -n 1)
                    export JAVA_HOME=\$PWD/\$JAVA_DIR
                    export PATH=\$JAVA_HOME/bin:\$PATH
                    java -version

                    ZAP_PATH=\$(find ${ZAP_DIR} -name zap.sh | head -n 1)

                    if [ -z "\$ZAP_PATH" ]; then
                        exit 1
                    fi
                    export JAVA_OPTS="-Xmx1024m"

                    nohup \$ZAP_PATH -daemon \
                        -port ${ZAP_PORT} \
                        -host 127.0.0.1 \
                        -config api.key=${ZAP_API_KEY} \
                        -config connection.timeoutInSecs=60 \
                        > zap.log 2>&1 &
                    sleep 60
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/core/view/version/?apikey=${ZAP_API_KEY}" || true
                """
            }

            stage('Register Frontend Endpoints') {
                sh """
                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/ || true

                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/salary-list || true

                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/attendance-list || true
                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/attendance-add || true

                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/employee-list || true
                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/employee-add || true

                    curl -X POST -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/attendance-add \
                        -H "Content-Type: application/json" \
                        -d '{"employee_id":"1","date":"2024-01-01","status":"present"}' || true

                    curl -X POST -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/employee-add \
                        -H "Content-Type: application/json" \
                        -d '{"name":"test","email":"test@test.com","department":"IT"}' || true

                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/test || true
                    curl -x http://127.0.0.1:${ZAP_PORT} ${TARGET_URL}/admin || true

                """
            }

            stage('Run Spider Scan') {
                echo "Running Spider Scan on all frontend paths..."
                sh """
                    echo "Spidering base URL..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/&apikey=${ZAP_API_KEY}"

                    echo "Spidering salary-list..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/salary-list&apikey=${ZAP_API_KEY}"

                    echo "Spidering attendance-list..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/attendance-list&apikey=${ZAP_API_KEY}"

                    echo "Spidering attendance-add..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/attendance-add&apikey=${ZAP_API_KEY}"

                    echo "Spidering employee-list..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/employee-list&apikey=${ZAP_API_KEY}"

                    echo "Spidering employee-add..."
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/employee-add&apikey=${ZAP_API_KEY}"

                    echo "Waiting for Spider to complete..."
                    sleep 30
                    echo "Spider done"
                """
            }

            stage('Generate Report') {
                echo "Generating ZAP HTML report..."
                sh """
                    mkdir -p ${REPORT_DIR}
                    curl "http://127.0.0.1:${ZAP_PORT}/OTHER/core/other/htmlreport/?apikey=${ZAP_API_KEY}" \
                        -o ${REPORT_DIR}/zap_frontend_report.html
                    echo "Report generated"
                    ls -lh ${REPORT_DIR}/
                """
            }

            stage('Archive Report') {
                echo "Archiving report..."
                archiveArtifacts artifacts: "${REPORT_DIR}/*.html", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (Exception err) {
            currentBuild.result = 'FAILURE'
            echo "Pipeline failed: ${err}"
            throw err

        } finally {

            stage('Post Actions') {
                echo "Cleaning up ZAP process..."
                sh """
                    pkill -f zap.sh || true
                    rm -rf ${ZAP_DIR} || true
                """

                if (currentBuild.result == 'FAILURE') {
                    slackSend(
                        channel: SLACK_CHANNEL,
                        color: 'danger',
                        message: "FAILED - OWASP ZAP Scan (Frontend)\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\nURL: ${env.BUILD_URL}"
                    )
                } else {
                    slackSend(
                        channel: SLACK_CHANNEL,
                        color: 'good',
                        message: "SUCCESS - OWASP ZAP Scan (Frontend)\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\nURL: ${env.BUILD_URL}"
                    )
                }
                cleanWs()
            }
        }
    }
}
