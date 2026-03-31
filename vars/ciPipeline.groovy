
def unitTest() {
    echo "========== [Unit Test] Running pytest =========="
    sh """
        pip install --upgrade pip -q
        pip install -r requirements.txt -q
        pip install pytest pytest-cov -q
        pytest tests/ \
            --cov=. \
            --cov-report=xml:coverage.xml \
            --cov-report=html:coverage-html \
            --junitxml=test-results.xml \
            -v || true
    """
    junit allowEmptyResults: true, testResults: 'test-results.xml'
    publishHTML(target: [
        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
        reportDir: 'coverage-html', reportFiles: 'index.html',
        reportName: 'PyTest Coverage Report'
    ])
    echo "========== [Unit Test] Done =========="
}

// ─── Static Code Analysis + Bug Analysis (pylint + SonarQube) ────────────────
def staticCodeAnalysis() {
    echo "========== [Static Code Analysis] Running pylint + SonarQube =========="
    sh """
        pip install pylint -q
        python3 -m pylint \$(find . -name "*.py" ! -path "./tests/*" ! -path "./.venv/*") \
            --output-format=parseable --exit-zero > pylint-report.txt || true
    """
    withSonarQubeEnv('SonarQube') {
        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
            sh """
                sonar-scanner \
                    -Dsonar.projectKey=notification-worker \
                    -Dsonar.projectName="Notification Worker" \
                    -Dsonar.sources=. \
                    -Dsonar.language=py \
                    -Dsonar.exclusions="**/tests/**,**/__pycache__/**,**/venv/**" \
                    -Dsonar.python.pylint.reportPaths=pylint-report.txt \
                    -Dsonar.python.coverage.reportPaths=coverage.xml \
                    -Dsonar.login=\$SONAR_TOKEN
            """
        }
    }
    timeout(time: 5, unit: 'MINUTES') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK') {
            error "SonarQube Quality Gate FAILED: ${qg.status}"
        }
    }
    echo "========== [Static Code Analysis] Done =========="
}

// ─── Dependency Scan (Trivy) ──────────────────────────────────────────────────
def dependencyScan() {
    echo "========== [Dependency Scan] Running Trivy =========="
    sh """
        if ! command -v trivy &> /dev/null; then
            curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                | sh -s -- -b /usr/local/bin
        fi
        trivy fs \
            --format table \
            --severity HIGH,CRITICAL \
            --exit-code 0 \
            . | tee trivy-report.txt

        trivy fs \
            --format json \
            --severity HIGH,CRITICAL \
            --exit-code 0 \
            --output trivy-report.json \
            .
    """
    archiveArtifacts artifacts: 'trivy-report.txt, trivy-report.json', allowEmptyArchive: true
    echo "========== [Dependency Scan] Done =========="
}
