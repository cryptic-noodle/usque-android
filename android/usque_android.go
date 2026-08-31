// Package usqueandroid provides Android-callable functions for the usque VPN library.
// This package is designed to be compiled with gomobile bind to produce an .aar file.
//
// Build with:
//
//	gomobile bind -v -target=android/arm64,android/arm -androidapi 24 -o usque.aar github.com/Diniboy1123/usque/android
package usqueandroid

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Diniboy1123/usque/api"
	"github.com/Diniboy1123/usque/config"
	"github.com/Diniboy1123/usque/internal"
)

// Log levels
const (
	LogLevelDebug = 0
	LogLevelInfo  = 1
	LogLevelWarn  = 2
	LogLevelError = 3
)

// LogListener is an interface for streaming logs to Android UI in real-time
type LogListener interface {
	OnLogMessage(message string)
}

// ringLogBuffer holds log lines in memory
type ringLogBuffer struct {
	mu       sync.Mutex
	lines    []string
	maxLines int
	listener LogListener
}

func newRingLogBuffer(maxLines int) *ringLogBuffer {
	return &ringLogBuffer{
		lines:    make([]string, 0, maxLines),
		maxLines: maxLines,
	}
}

func (b *ringLogBuffer) Write(p []byte) (n int, err error) {
	msg := strings.TrimRight(string(p), "\r\n")
	if msg == "" {
		return len(p), nil
	}

	b.mu.Lock()
	if len(b.lines) >= b.maxLines {
		b.lines = b.lines[1:]
	}
	b.lines = append(b.lines, msg)
	listener := b.listener
	b.mu.Unlock()

	if listener != nil {
		listener.OnLogMessage(msg)
	}

	// Also write to stderr
	return os.Stderr.Write(p)
}

func (b *ringLogBuffer) GetLogs() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return strings.Join(b.lines, "\n")
}

func (b *ringLogBuffer) Clear() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lines = b.lines[:0]
}

func (b *ringLogBuffer) SetListener(l LogListener) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.listener = l
}

var logBuffer = newRingLogBuffer(1000)

func init() {
	// Prepend timestamps and pipe to our ring buffer
	w := internal.NewTZStampWriter(logBuffer)
	log.SetFlags(0)
	log.SetPrefix("")
	log.SetOutput(w)
}

// Log management API for Android

// GetLogs returns the accumulated log history as a single string
func GetLogs() string {
	return logBuffer.GetLogs()
}

// ClearLogs clears the in-memory log buffer
func ClearLogs() {
	logBuffer.Clear()
}

// SetLogListener sets a listener for live log streaming
func SetLogListener(listener LogListener) {
	logBuffer.SetListener(listener)
}

// Log levels
var currentLogLevel = LogLevelInfo

// SetLogLevel sets current logging verbosity (0=DEBUG, 1=INFO, 2=WARN, 3=ERROR)
func SetLogLevel(level int) {
	currentLogLevel = level
	log.Printf("Log level set to %d", level)
}

// GetLogLevel returns current log level
func GetLogLevel() int {
	return currentLogLevel
}

// LogMessage logs a message from Android side with a given level
func LogMessage(level int, tag string, msg string) {
	if level < currentLogLevel {
		return
	}
	prefix := "INFO"
	switch level {
	case LogLevelDebug:
		prefix = "DEBUG"
	case LogLevelWarn:
		prefix = "WARN"
	case LogLevelError:
		prefix = "ERROR"
	}
	log.Printf("[%s] [%s] %s", prefix, tag, msg)
}

// PacketFlow is the interface that Android must implement to exchange packets with the VPN
// This interface is used for bidirectional packet flow between Android TUN and Go tunnel
type PacketFlow interface {
	// WritePacket writes an IP packet to the Android TUN device
	// Called by Go when a packet is received from Cloudflare
	WritePacket(data []byte)
}

// VpnStateCallback is the interface for VPN state notifications
type VpnStateCallback interface {
	// OnConnected is called when the VPN successfully connects to Cloudflare
	OnConnected()
	// OnDisconnected is called when the VPN disconnects
	OnDisconnected(reason string)
	// OnError is called when an error occurs
	OnError(message string)
}

// tunnelState holds the state of the running tunnel
type tunnelState struct {
	mu        sync.Mutex
	running   bool
	cancel    context.CancelFunc
	inputChan chan []byte
	callback  VpnStateCallback
}

var state = &tunnelState{}

// Custom connection options
var (
	customSNI            = "www.visa.cn" // Default SNI for censorship circumvention
	customEndpoint       = ""            // Custom endpoint with port, e.g. "162.159.198.2:443" or "[2606:4700:103::]:1701"
	customUseHTTP2       = false         // HTTP/2 mode (default false = HTTP/3 / QUIC)
	customKeepaliveSec   = 30            // Keepalive period in seconds (default 30)
	customMTU            = 1280          // MTU size (default 1280)
	customAlwaysReconnect = true         // Always reconnect continuously (default true)
)

// Register creates a new Cloudflare WARP account and saves the configuration.
// This should be called once before starting the VPN.
//
// Parameters:
//   - configPath: Absolute path where the config.json will be saved
//   - deviceName: Optional device name (can be empty)
//
// Returns:
//   - error string if registration fails, empty string on success
func Register(configPath string, deviceName string) string {
	// Already registered?
	if err := config.LoadConfig(configPath); err == nil {
		return "" // Config already exists and is valid
	}

	accountData, err := api.Register(internal.DefaultModel, internal.DefaultLocale, "", true)
	if err != nil {
		return fmt.Sprintf("Registration failed: %v", err)
	}

	privKey, pubKey, err := internal.GenerateEcKeyPair()
	if err != nil {
		return fmt.Sprintf("Failed to generate key pair: %v", err)
	}

	updatedAccountData, err := api.EnrollKey(accountData.ID, accountData.Token, pubKey, deviceName)
	if err != nil {
		return fmt.Sprintf("Failed to enroll key: %v", err)
	}

	config.AppConfig = config.Config{
		PrivateKey:     base64.StdEncoding.EncodeToString(privKey),
		EndpointV4:     updatedAccountData.Config.Peers[0].Endpoint.V4[:len(updatedAccountData.Config.Peers[0].Endpoint.V4)-2],
		EndpointV6:     updatedAccountData.Config.Peers[0].Endpoint.V6[1 : len(updatedAccountData.Config.Peers[0].Endpoint.V6)-3],
		EndpointH2V4:   config.DefaultEndpointH2V4,
		EndpointH2V6:   config.DefaultEndpointH2V6,
		EndpointPubKey: updatedAccountData.Config.Peers[0].PublicKey,
		ID:             updatedAccountData.ID,
		AccessToken:    accountData.Token,
		IPv4:           updatedAccountData.Config.Interface.Addresses.V4,
		IPv6:           updatedAccountData.Config.Interface.Addresses.V6,
	}

	if err := config.AppConfig.SaveConfig(configPath); err != nil {
		return fmt.Sprintf("Failed to save config: %v", err)
	}

	return ""
}

// IsRegistered checks if a valid configuration exists
func IsRegistered(configPath string) bool {
	return config.LoadConfig(configPath) == nil
}

// GetAssignedIPv4 returns the assigned IPv4 address from config
func GetAssignedIPv4(configPath string) string {
	if err := config.LoadConfig(configPath); err != nil {
		return ""
	}
	return config.AppConfig.IPv4
}

// GetAssignedIPv6 returns the assigned IPv6 address from config
func GetAssignedIPv6(configPath string) string {
	if err := config.LoadConfig(configPath); err != nil {
		return ""
	}
	return config.AppConfig.IPv6
}

// AndroidTunDevice wraps the Android TUN file descriptor for packet IO
type AndroidTunDevice struct {
	fd       int
	file     *os.File
	mtu      int
	inputCh  chan []byte
	outputFn PacketFlow
}

// newAndroidTunDevice creates a new Android TUN device wrapper
func newAndroidTunDevice(fd int, mtu int, packetFlow PacketFlow) (*AndroidTunDevice, error) {
	file := os.NewFile(uintptr(fd), "tun")
	if file == nil {
		return nil, fmt.Errorf("failed to create file from fd %d", fd)
	}

	return &AndroidTunDevice{
		fd:       fd,
		file:     file,
		mtu:      mtu,
		inputCh:  make(chan []byte, 256),
		outputFn: packetFlow,
	}, nil
}

func (d *AndroidTunDevice) ReadPacket(buf []byte) (int, error) {
	n, err := d.file.Read(buf)
	if err != nil {
		return 0, err
	}
	return n, nil
}

func (d *AndroidTunDevice) WritePacket(pkt []byte) error {
	if d.outputFn != nil {
		// Use the callback to write to Android TUN
		d.outputFn.WritePacket(pkt)
		return nil
	}
	// Fallback to direct write
	_, err := d.file.Write(pkt)
	return err
}

func (d *AndroidTunDevice) Close() error {
	if d.file != nil {
		return d.file.Close()
	}
	return nil
}

// StartTunnel starts the VPN tunnel using the provided TUN file descriptor.
// This function connects directly to Cloudflare WARP and forwards all traffic.
//
// Parameters:
//   - configPath: Path to the config.json file
//   - tunFd: The file descriptor of the Android TUN interface
//   - mtu: MTU size (usually 1280)
//   - packetFlow: Interface for writing packets back to Android TUN
//   - callback: State callback interface (can be nil)
//
// Returns:
//   - error string if startup fails, empty string on success
func StartTunnel(configPath string, tunFd int, mtu int, packetFlow PacketFlow, callback VpnStateCallback) string {
	state.mu.Lock()
	defer state.mu.Unlock()

	if state.running {
		return "Tunnel is already running"
	}

	if mtu <= 0 {
		mtu = customMTU
	}
	if mtu <= 0 {
		mtu = 1280
	}

	log.Printf("StartTunnel called: configPath=%s, tunFd=%d, mtu=%d, http2=%v, alwaysReconnect=%v",
		configPath, tunFd, mtu, customUseHTTP2, customAlwaysReconnect)

	// Load config
	if err := config.LoadConfig(configPath); err != nil {
		return fmt.Sprintf("Failed to load config: %v", err)
	}

	// Get keys
	privKey, err := config.AppConfig.GetEcPrivateKey()
	if err != nil {
		return fmt.Sprintf("Failed to get private key: %v", err)
	}
	peerPubKey, err := config.AppConfig.GetEcEndpointPublicKey()
	if err != nil {
		return fmt.Sprintf("Failed to get peer public key: %v", err)
	}

	// Generate certificate
	cert, err := internal.GenerateCert(privKey, &privKey.PublicKey)
	if err != nil {
		return fmt.Sprintf("Failed to generate cert: %v", err)
	}

	// Prepare TLS config with custom SNI
	sni := customSNI
	if sni == "" {
		sni = internal.ConnectSNI
	}
	log.Printf("Using SNI: %s", sni)
	tlsConfig, err := api.PrepareTlsConfig(privKey, peerPubKey, cert, sni, false)
	if err != nil {
		return fmt.Sprintf("Failed to prepare TLS: %v", err)
	}

	// Create Android TUN device wrapper
	tunDevice, err := newAndroidTunDevice(tunFd, mtu, packetFlow)
	if err != nil {
		return fmt.Sprintf("Failed to create TUN device: %v", err)
	}

	// Endpoint selection
	var endpoint net.Addr
	if customEndpoint != "" {
		host, port, err := parseEndpoint(customEndpoint)
		if err != nil {
			return fmt.Sprintf("Invalid custom endpoint '%s': %v", customEndpoint, err)
		}
		if customUseHTTP2 {
			endpoint = &net.TCPAddr{
				IP:   net.ParseIP(host),
				Port: port,
			}
			log.Printf("Using custom HTTP/2 endpoint: %s:%d", host, port)
		} else {
			endpoint = &net.UDPAddr{
				IP:   net.ParseIP(host),
				Port: port,
			}
			log.Printf("Using custom QUIC endpoint: %s:%d", host, port)
		}
	} else {
		selectedEndpoint, err := config.SelectEndpointFromConfig(customUseHTTP2, false, 443)
		if err != nil {
			return fmt.Sprintf("Failed to select endpoint: %v", err)
		}
		endpoint = selectedEndpoint
		log.Printf("Using default endpoint: %s (HTTP/2: %v)", endpoint.String(), customUseHTTP2)
	}

	// Create context for cancellation
	ctx, cancel := context.WithCancel(context.Background())
	state.cancel = cancel
	state.running = true
	state.callback = callback

	keepalive := time.Duration(customKeepaliveSec) * time.Second
	if keepalive <= 0 {
		keepalive = 30 * time.Second
	}

	// Start tunnel maintenance in background
	go func() {
		log.Printf("Starting MASQUE tunnel maintenance (HTTP/2=%v, Keepalive=%v, MTU=%d)...",
			customUseHTTP2, keepalive, mtu)

		api.MaintainTunnel(ctx, api.MaintainTunnelConfig{
			TLSConfig:         tlsConfig,
			KeepalivePeriod:   keepalive,
			InitialPacketSize: 0, // 0 = automatic Path MTU Discovery (PMTUD enabled)
			Endpoint:          endpoint,
			Device:            tunDevice,
			MTU:               mtu,
			ReconnectDelay:    1 * time.Second,
			AlwaysReconnect:   customAlwaysReconnect,
			UseHTTP2:          customUseHTTP2,
		})

		// Tunnel exited
		log.Println("MASQUE tunnel exited")
		tunDevice.Close()

		state.mu.Lock()
		state.running = false
		state.mu.Unlock()

		if callback != nil {
			callback.OnDisconnected("Tunnel closed")
		}
	}()

	// Signal connected
	if callback != nil {
		go func() {
			time.Sleep(500 * time.Millisecond)
			callback.OnConnected()
		}()
	}

	log.Println("Tunnel started successfully")
	return ""
}

// InputPacket sends an IP packet from Android TUN to the Go tunnel.
func InputPacket(data []byte) {
	state.mu.Lock()
	ch := state.inputChan
	state.mu.Unlock()

	if ch != nil {
		select {
		case ch <- data:
		default:
		}
	}
}

// StopTunnel stops the running tunnel
func StopTunnel() {
	state.mu.Lock()
	defer state.mu.Unlock()

	if !state.running {
		return
	}

	log.Println("Stopping tunnel...")

	if state.cancel != nil {
		state.cancel()
	}

	state.running = false
}

// IsRunning returns true if the tunnel is currently running
func IsRunning() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.running
}

// GetVersion returns the library version
func GetVersion() string {
	return "1.0.4-android"
}

// parseEndpoint parses an endpoint string in the format:
// - "host:port" for IPv4 (e.g., "162.159.198.2:443")
// - "[host]:port" for IPv6 (e.g., "[2606:4700:103::]:1701")
// - "host" without port (defaults to 443)
func parseEndpoint(endpoint string) (string, int, error) {
	if len(endpoint) > 0 && endpoint[0] == '[' {
		closeBracket := -1
		for i, c := range endpoint {
			if c == ']' {
				closeBracket = i
				break
			}
		}
		if closeBracket == -1 {
			return "", 0, fmt.Errorf("missing closing bracket for IPv6 address")
		}

		host := endpoint[1:closeBracket]
		if closeBracket+1 < len(endpoint) && endpoint[closeBracket+1] == ':' {
			portStr := endpoint[closeBracket+2:]
			port, err := strconv.Atoi(portStr)
			if err != nil {
				return "", 0, fmt.Errorf("invalid port: %s", portStr)
			}
			return host, port, nil
		}
		return host, 443, nil
	}

	lastColon := -1
	for i := len(endpoint) - 1; i >= 0; i-- {
		if endpoint[i] == ':' {
			lastColon = i
			break
		}
	}

	if lastColon != -1 {
		host := endpoint[:lastColon]
		portStr := endpoint[lastColon+1:]
		port, err := strconv.Atoi(portStr)
		if err != nil {
			return "", 0, fmt.Errorf("invalid port: %s", portStr)
		}
		return host, port, nil
	}

	return endpoint, 443, nil
}

// ============================================
// Connection Configuration Functions
// ============================================

// SetSNI sets a custom SNI for the TLS connection.
func SetSNI(sni string) {
	customSNI = sni
	log.Printf("SNI set to: %s", sni)
}

// GetSNI returns the current SNI setting
func GetSNI() string {
	return customSNI
}

// SetEndpoint sets a custom endpoint for the MASQUE connection.
func SetEndpoint(endpoint string) {
	customEndpoint = endpoint
	log.Printf("Custom endpoint set to: %s", endpoint)
}

// GetEndpoint returns the current custom endpoint setting
func GetEndpoint() string {
	return customEndpoint
}

// SetHTTP2 enables or disables HTTP/2 transport mode
func SetHTTP2(enabled bool) {
	customUseHTTP2 = enabled
	log.Printf("HTTP/2 mode set to: %v", enabled)
}

// GetHTTP2 returns whether HTTP/2 transport mode is enabled
func GetHTTP2() bool {
	return customUseHTTP2
}

// SetKeepalivePeriod sets the keepalive duration in seconds (default 30)
func SetKeepalivePeriod(seconds int) {
	if seconds <= 0 {
		seconds = 30
	}
	customKeepaliveSec = seconds
	log.Printf("Keepalive period set to: %ds", seconds)
}

// GetKeepalivePeriod returns keepalive period in seconds
func GetKeepalivePeriod() int {
	return customKeepaliveSec
}

// SetMTU sets the tunnel MTU size (default 1280)
func SetMTU(mtu int) {
	if mtu <= 0 {
		mtu = 1280
	}
	customMTU = mtu
	log.Printf("MTU set to: %d", mtu)
}

// GetMTU returns current MTU size
func GetMTU() int {
	return customMTU
}

// SetAlwaysReconnect sets whether to reconnect continuously regardless of idle state
func SetAlwaysReconnect(enabled bool) {
	customAlwaysReconnect = enabled
	log.Printf("AlwaysReconnect set to: %v", enabled)
}

// GetAlwaysReconnect returns whether AlwaysReconnect is enabled
func GetAlwaysReconnect() bool {
	return customAlwaysReconnect
}

// GetDefaultEndpoint returns the default endpoint from config for current mode
func GetDefaultEndpoint(configPath string) string {
	if err := config.LoadConfig(configPath); err == nil {
		if customUseHTTP2 {
			if config.AppConfig.EndpointH2V4 != "" {
				return config.AppConfig.EndpointH2V4 + ":443"
			}
			return config.DefaultEndpointH2V4 + ":443"
		}
		return config.AppConfig.EndpointV4 + ":443"
	}
	if customUseHTTP2 {
		return config.DefaultEndpointH2V4 + ":443"
	}
	return "162.159.198.1:443"
}

// ResetConnectionOptions resets all connection options to defaults
func ResetConnectionOptions() {
	customSNI = "www.visa.cn"
	customEndpoint = ""
	customUseHTTP2 = false
	customKeepaliveSec = 30
	customMTU = 1280
	customAlwaysReconnect = true
	log.Println("Connection options reset to defaults")
}

// ============================================
// File Descriptor based helper
// ============================================

// StartTunnelWithFd starts the tunnel by reading/writing directly to the TUN fd.
func StartTunnelWithFd(configPath string, tunFd int, callback VpnStateCallback) string {
	return StartTunnel(configPath, tunFd, customMTU, nil, callback)
}

// fdReadWriter wraps a file descriptor for io.ReadWriter
type fdReadWriter struct {
	file *os.File
}

func (f *fdReadWriter) Read(p []byte) (n int, err error) {
	return f.file.Read(p)
}

func (f *fdReadWriter) Write(p []byte) (n int, err error) {
	return f.file.Write(p)
}

// CreateTunReadWriter creates an io.ReadWriter from a TUN file descriptor
func CreateTunReadWriter(fd int) io.ReadWriter {
	file := os.NewFile(uintptr(fd), "tun")
	return &fdReadWriter{file: file}
}
