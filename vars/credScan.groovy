def call(reportDir) {
    stage('Credential Scan') {
        sh """
        mkdir -p ${reportDir}

        gitleaks detect \
        --source . \
        --report-format json \
        --report-path ${reportDir}/gitleaks-report.json \
        --exit-code 1
        """
    }
}
