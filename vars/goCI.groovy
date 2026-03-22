def call(Map config = [:]) {

    def appPath    = config.path ?: '.'
    def binaryName = config.binary ?: 'app-binary'
    def testReport = config.testReport ?: 'test-report.json'

    echo "Starting Golang CI (Production Ready)"

    sh """
    set -e

    echo "=============================="
    echo "Installing Go (Isolated)"
    echo "=============================="

    GO_VERSION="1.21.6"

    # Download Go
    curl -LO https://go.dev/dl/go\${GO_VERSION}.linux-amd64.tar.gz

    # Install OUTSIDE workspace (important fix)
    mkdir -p /tmp/go-install
    tar -xzf go\${GO_VERSION}.linux-amd64.tar.gz -C /tmp/
    mv /tmp/go /tmp/go-install

    # Set PATH
    export PATH=/tmp/go-install/bin:\$PATH

    echo "=============================="
    echo "Go Environment"
    echo "=============================="
    go version

    echo "=============================="
    echo "Switching to App Directory"
    echo "=============================="
    cd ${appPath}

    echo "=============================="
    echo "Preparing Dependencies"
    echo "=============================="
    go mod tidy
    go mod download

    echo "=============================="
    echo "Running Unit Tests"
    echo "=============================="

    # Run only project packages (IMPORTANT FIX)
    go test \$(go list ./...) -v -json | tee ${testReport}

    echo "=============================="
    echo "Test Summary"
    echo "=============================="

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
