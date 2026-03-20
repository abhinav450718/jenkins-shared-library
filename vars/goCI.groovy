def call(Map config = [:]) {

    def appPath = config.path ?: '.'
    def binaryName = config.binary ?: 'app-binary'
    def testReport = config.testReport ?: 'test-report.json'

    echo "Starting Golang CI (Detailed Insights)"

    sh """
    set -e

    echo "=============================="
    echo "Go Environment"
    echo "=============================="
    go version

    cd ${appPath}

    echo "=============================="
    echo "Preparing Dependencies"
    echo "=============================="
    go mod tidy
    go mod download

    echo "=============================="
    echo "Running Unit Tests"
    echo "=============================="

    # Run tests (console + save JSON)
    go test ./... -v -json | tee ${testReport} || echo "Tests completed with some failures"

    echo "=============================="
    echo "Test Summary"
    echo "=============================="

    # Count PASS / FAIL
    PASS_COUNT=\$(grep -c '"Action":"pass"' ${testReport} || true)
    FAIL_COUNT=\$(grep -c '"Action":"fail"' ${testReport} || true)

    echo "Passed Tests: \$PASS_COUNT"
    echo "Failed Tests: \$FAIL_COUNT"

    echo "=============================="
    echo "Building Application"
    echo "=============================="

    go build -o ${binaryName} .

    echo "Build Successful"

    echo "=============================="
    echo "Binary Details"
    echo "=============================="
    ls -lh ${binaryName}

    echo "=============================="
    echo "Artifacts Generated"
    echo "=============================="
    ls -lh ${binaryName} ${testReport}
    """

    echo "Archiving artifacts..."

    archiveArtifacts artifacts: "${binaryName}, ${testReport}", fingerprint: true
}
