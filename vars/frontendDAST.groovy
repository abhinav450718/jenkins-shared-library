def call(Map config) {

    def TARGET_URL    = config.targetUrl    ?: "http://98.80.140.40"
    def ZAP_PORT      = config.zapPort      ?: "9000"
    def ZAP_API_KEY   = config.zapApiKey    ?: "frontendkey"
    def REPORT_DIR    = config.reportDir    ?: "zap-reports"
    def ZAP_DIR       = config.zapDir       ?: "zap"
    def SLACK_CHANNEL = config.slackChannel ?: "#ci-operation-notifications"

    node {

        try {

            stage('Clean Workspace') {
                cleanWs()
            }

            stage('Verify Frontend Accessibility') {
                sh """
                    HTTP_CODE=\$(curl -o /dev/null -s -w "%{http_code}" --max-time 10 ${TARGET_URL}/ || echo "000")
                    if [ "\$HTTP_CODE" = "000" ]; then
                        exit 1
                    fi
                """
            }

            stage('Setup Java 17') {
                sh '''
                    mkdir -p java
                    cd java
                    wget -q https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                    tar -xzf *.tar.gz
                    rm -f *.tar.gz
                '''
            }

            stage('Download OWASP ZAP') {
                sh """
                    mkdir -p ${ZAP_DIR}
                    cd ${ZAP_DIR}
                    wget -q https://github.com/zaproxy/zaproxy/releases/download/v2.17.0/ZAP_2.17.0_Linux.tar.gz
                    tar -xzf *.tar.gz
                    rm -f *.tar.gz
                """
            }

            stage('Start ZAP') {
                sh """
                    JAVA_DIR=\$(ls -d java/jdk-17* | head -n 1)
                    export JAVA_HOME=\$PWD/\$JAVA_DIR
                    export PATH=\$JAVA_HOME/bin:\$PATH

                    ZAP_PATH=\$(find ${ZAP_DIR} -name zap.sh | head -n 1)

                    nohup \$ZAP_PATH -daemon \
                        -port ${ZAP_PORT} \
                        -host 127.0.0.1 \
                        -config api.key=${ZAP_API_KEY} \
                        > zap.log 2>&1 &

                    sleep 60
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
                """
            }

            stage('Run Spider Scan') {
                sh """
                    curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/action/scan/?url=${TARGET_URL}/&apikey=${ZAP_API_KEY}"

                    STATUS=0
                    while [ "\$STATUS" != "100" ]; do
                        STATUS=\$(curl -s "http://127.0.0.1:${ZAP_PORT}/JSON/spider/view/status/?scanId=0&apikey=${ZAP_API_KEY}" | grep -o '[0-9]*')
                        sleep 5
                    done
                """
            }

            stage('Generate Report') {
                sh """
                    mkdir -p ${REPORT_DIR}
                    curl "http://127.0.0.1:${ZAP_PORT}/OTHER/core/other/htmlreport/?apikey=${ZAP_API_KEY}" \
                        -o ${REPORT_DIR}/zap_frontend_report.html
                """
            }

            stage('Archive Report') {
                archiveArtifacts artifacts: "${REPORT_DIR}/*.html", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (Exception err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {

            stage('Post Actions') {
                def status    = currentBuild.result ?: 'FAILURE'
                def zapReport = "${env.BUILD_URL}artifact/${REPORT_DIR}/zap_frontend_report.html"

                if (status == 'FAILURE') {
                    slackSend(
                        channel: SLACK_CHANNEL,
                        color: 'danger',
                        message: "*FAILED* - OWASP ZAP Frontend Security Scan\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Target URL:*     " + TARGET_URL + "\n" +
                                 "*Status:*         Build Failed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>"
                    )
                } else {
                    slackSend(
                        channel: SLACK_CHANNEL,
                        color: 'good',
                        message: "*SUCCESS* - OWASP ZAP Frontend Security Scan\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Target URL:*     " + TARGET_URL + "\n" +
                                 "*Status:*         Scan Completed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>   |   " +
                                 "<" + zapReport + "|ZAP HTML Report>"
                    )
                }

                sh """
                    pkill -f zap.sh || true
                    rm -rf ${ZAP_DIR} || true
                """
                cleanWs()
            }
        }
    }
}
