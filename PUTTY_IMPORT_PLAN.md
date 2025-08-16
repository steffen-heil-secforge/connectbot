# PuTTY to ConnectBot Import Mapping Plan

## Overview

This document outlines the mapping strategy for importing SSH host configurations from PuTTY Windows Registry exports (.reg files) into ConnectBot's SQLite database format.

## Direct Attribute Mappings

| PuTTY Registry Key | ConnectBot HostBean Field | Notes |
|-------------------|---------------------------|-------|
| **Session Name** | `nickname` | Extract from registry key path |
| `HostName` | `hostname` | Direct mapping |
| `UserName` | `username` | Direct mapping |
| `PortNumber` | `port` | Convert DWORD to int |
| `Protocol` | `protocol` | Map "ssh" → "ssh" |
| `Compression` | `compression` | Convert DWORD to boolean |
| `PublicKeyFile` | `pubkeyId` | Requires key import/lookup |

## Port Forwarding Mappings

| PuTTY Format | ConnectBot PortForwardBean | Notes |
|-------------|---------------------------|-------|
| `PortForwardings` string | Multiple PortForwardBean records | Parse comma-separated |
| `L<port>=<host>:<port>` | `type="local"`, `sourcePort`, `destAddr`, `destPort` | Local forward |
| `R<port>=<host>:<port>` | `type="remote"`, `sourcePort`, `destAddr`, `destPort` | Remote forward |
| `D<port>` | `type="dynamic5"`, `sourcePort` | SOCKS proxy |
| `4L<port>=<host>:<port>` | IPv4 preference | ConnectBot doesn't distinguish |

### Port Forward Examples

```
PuTTY: "L1022=192.168.168.128:22,L1023=192.168.168.128:5900,L64734=127.0.0.1:64734"
→ 3 PortForwardBean records:
  - type="local", sourcePort=1022, destAddr="192.168.168.128", destPort=22
  - type="local", sourcePort=1023, destAddr="192.168.168.128", destPort=5900
  - type="local", sourcePort=64734, destAddr="127.0.0.1", destPort=64734

PuTTY: "4L13050=127.0.0.1:3050"
→ 1 PortForwardBean record:
  - type="local", sourcePort=13050, destAddr="127.0.0.1", destPort=3050
```

## Default Value Mappings

| ConnectBot Field | Default Value | Source |
|-----------------|---------------|---------|
| `useKeys` | `true` | PuTTY default behavior |
| `useAuthAgent` | `"yes"` if `TryAgent=1` | Map from `TryAgent` |
| `wantSession` | `true` | Standard SSH session |
| `delKey` | `"del"` | ConnectBot default |
| `encoding` | `"UTF-8"` | Modern default |
| `stayConnected` | `false` | Default behavior |
| `quickDisconnect` | `false` | Default behavior |
| `bindAddress` | `"localhost"` | Port forward default |

## Important Options That CANNOT Be Mapped

### SSH Protocol Features
- **Cipher preferences** (`Cipher`): ConnectBot uses library defaults
- **KEX algorithms** (`KEX`): ConnectBot doesn't expose configuration
- **Host key preferences** (`HostKey`): ConnectBot handles automatically
- **SSH protocol bugs** (`Bug*` keys): ConnectBot doesn't have workarounds
- **GSSAPI settings** (`AuthGSSAPI*`): Not supported in ConnectBot
- **Certificate authentication** (`DetachedCertificate`): Not supported

### Terminal Emulation
- **Terminal type** (`TerminalType`): ConnectBot uses VT320
- **Terminal modes** (`TerminalModes`): Not configurable
- **Character encoding** (beyond UTF-8): Limited ConnectBot support
- **Bell settings**: ConnectBot uses system defaults

### Advanced Features
- **Connection sharing** (`ConnectionSharing*`): Not supported
- **Serial connections**: ConnectBot is SSH/Telnet only
- **Raw protocol**: ConnectBot doesn't support
- **Proxy settings** (`Proxy*`): ConnectBot doesn't support proxies
- **X11 forwarding** (`X11Forward`): Not supported on Android

### UI/Display Settings
- **Font settings** (`Font`, `FontHeight`, `FontIsBold`): ConnectBot uses system font preferences
- **Color schemes** (ANSI color maps): ConnectBot has preset colors
- **Window behavior** (`AlwaysOnTop`, etc.): Android app behavior
- **Scrollback settings**: ConnectBot manages automatically

## Implementation Strategy

### User Interface Flow

1. **Host List Menu Entry**
   - Add "Import PuTTY targets" option to 3-dot menu in HostListActivity
   - Launch file picker to select .reg files
   - Show progress dialog during file parsing

2. **File Validation**
   - Parse selected .reg file for PuTTY registry structure
   - Check for `[HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\*]` sections
   - Ignore all non-PuTTY registry entries
   - Show error dialog if no valid PuTTY configurations found

3. **Session Selection Dialog**
   - Display list of found SSH sessions with checkboxes
   - Show session details: nickname, hostname, username, port, port forward count
   - Mark existing hosts differently (will be updated vs new creation)
   - Hide sessions that already exist with identical configuration
   - Include "Select All" / "Deselect All" options
   - Allow user to choose which sessions to import

4. **Port Forward Bind Address Configuration**
   - Global setting for all imported port forwards
   - Radio buttons: "WiFi Hotspot (AP)" (default), "All interfaces (0.0.0.0)", "Localhost"
   - Apply to all port forwards in selected sessions

5. **Import Execution**
   - Create/update HostBean objects for selected sessions
   - Update existing hosts if nickname matches (no duplicates)
   - Import associated port forwards with chosen bind address
   - Show import summary dialog with success/failure counts

### Implementation Components

#### 1. File Parsing (`PuttyRegistryParser.java`)
```java
public class PuttyRegistryParser {
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_SESSIONS = 100;

    public ParseResult parseRegistryFile(InputStream regFile);
    public boolean isValidPuttyRegistry(InputStream regFile);
    private PuttySession parseSessionSection(String sessionName, Map<String, String> values);
    private boolean validateSessionName(String name);
    private boolean validateHostname(String hostname);
    private boolean validateUsername(String username);
    private boolean validatePort(int port);
    private boolean validatePortForward(String forward);
}

public class ParseResult {
    private List<PuttySession> validSessions;
    private List<String> errors;
    private List<String> warnings;
    private boolean truncated; // true if >100 sessions found
}
```

#### 2. Import Dialog (`PuttyImportDialog.java`)
```java
public class PuttyImportDialog extends DialogFragment {
    private ParseResult parseResult;
    private List<PuttySession> availableSessions;
    private RecyclerView sessionList;
    private RadioGroup bindAddressGroup;
    private Button importButton;
    private TextView warningText; // Show parsing warnings/truncation

    public static PuttyImportDialog newInstance(ParseResult result) {
        // Show warnings if sessions were skipped or truncated
    }
}
```

#### 3. Session List Adapter (`PuttySessionAdapter.java`)
```java
public class PuttySessionAdapter extends RecyclerView.Adapter<SessionViewHolder> {
    private List<PuttySession> sessions;
    private SparseBooleanArray selectedSessions;
    // Handle checkbox selection and session display
}
```

#### 4. Import Service (`PuttyImportService.java`)
```java
public class PuttyImportService {
    public ImportResult importSessions(List<PuttySession> sessions, String bindAddress);
    private HostBean convertToHostBean(PuttySession session);
    private List<PortForwardBean> convertPortForwards(PuttySession session, String bindAddress);
    private void updateOrCreateHost(HostBean host);

    // Exception handling: catch all exceptions, show generic error message
    // Pre-validation should prevent failures, so exceptions are unexpected
}
```

### Database Update Logic

#### Host Handling
- **Existing Host**: If `nickname` matches existing HostBean, update all fields
- **New Host**: Create new HostBean record
- **Port Forwards**: Delete existing forwards for host, create new ones

#### Bind Address Mapping
```java
public enum BindAddressOption {
    LOCALHOST("localhost"),
    ALL_INTERFACES("0.0.0.0"),
    WIFI_HOTSPOT(NetworkUtils.BIND_ACCESS_POINT);
}
```

### File Format Handling

#### File Validation and Security
- **File type restriction**: Accept only `.reg` files via MIME type filtering
- **File size limit**: Maximum 1MB file size to prevent DoS attacks
- **Session limit**: Maximum 100 sessions per import to prevent resource exhaustion
- **Encoding validation**: Carefully parse UTF-16 with BOM, reject malformed encoding
- **Input sanitization**: Validate all parsed fields before storage

#### Registry File Structure
- **Encoding detection**: Full BOM support (UTF-8, UTF-16 LE, UTF-16 BE) → UTF-8 fallback → system default
- **Content validation**: Verify basic registry file structure before detailed parsing
- **Path validation**: Only accept exact PuTTY registry paths, reject fake paths
- Parse `[HKEY_*]` sections with malformed section rejection
- Extract key-value pairs: `"Key"=value` with syntax validation
- Decode URL-encoded session names with path traversal protection
- Ignore all non-PuTTY registry sections

#### Field Validation Rules
```java
// Session validation
- Session name: Max 64 chars, safe ASCII chars (exclude control chars, path separators)
- Hostname: Valid FQDN, IPv4, or IPv6 address, max 253 chars
- Username: Max 32 chars, printable ASCII only (international chars excluded)
- Port: Range 1-65535
- Protocol: Must be "ssh" (others ignored)
- Duplicate detection: Reject duplicate session names within same file (after Unicode normalization)

// Port forward validation
- Source port: Range 1-65535
- Bind IP: Optional bind address (IPv4/IPv6) for local/dynamic forwards
- Destination host: Valid hostname/IP (same rules as session hostname)
- Destination port: Range 1-65535
- Forward type: L/R/D only (4L treated as L)
```

#### Parsing Strategy
1. **Pre-validation phase**: Parse entire file and validate all fields
2. **Session filtering**: Skip invalid sessions, continue with valid ones
3. **Error collection**: Collect all parsing errors for user feedback
4. **Safe presentation**: Only show pre-validated sessions in dialog

#### Error Handling
- Invalid file format → Show error dialog with details
- File too large (>1MB) → Show size limit error
- No PuTTY sessions found → Show error dialog
- Malformed encoding → Reject file with encoding error
- Invalid session data → Skip session, provide user-friendly error messages
- Corrupted file content → Reject file with integrity error
- Duplicate sessions in file → Reject duplicates after Unicode normalization
- Too many sessions (>100) → Import first 100, show truncation warning

#### URI Handling
- **Content URIs and File URIs**: Use `ContentResolver.openInputStream(uri)` for all URI types
- Handles both `content://` and `file://` schemes transparently
- Compatible with all Android file picker implementations

#### Accepted Limitations
- File size limit of 1MB may exclude large enterprise files
- International usernames not supported (ASCII only)
- Global bind address setting only (not per-port-forward)
- No selective import of connection details vs port forwards
- No detailed partial success reporting
- File picker MIME type handling varies by device
- Port forward replacement timing (delete before create)
- Foreign key constraints are not a concern for host updates
- No transaction isolation - partial import failures accepted
- Generic error handling for unexpected import failures (post-validation)

### UI Integration Points

#### HostListActivity Changes
```java
// Add menu item
@Override
public boolean onOptionsItemSelected(MenuItem item) {
    case R.id.menu_import_putty:
        launchPuttyImport();
        return true;
}

private void launchPuttyImport() {
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("text/plain");
    intent.putExtra("android.intent.extra.MIME_TYPES",
                   new String[]{"text/plain", "application/octet-stream"});
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent, REQUEST_IMPORT_PUTTY);
}
```

#### Menu Resource Addition
```xml
<item android:id="@+id/menu_import_putty"
      android:title="@string/import_putty_targets"
      android:icon="@drawable/ic_import" />
```

## Registry File Structure Example

```
[HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\server_name]
"Present"=dword:00000001
"HostName"="192.168.1.100"
"UserName"="admin"
"Protocol"="ssh"
"PortNumber"=dword:00000016
"Compression"=dword:00000000
"TryAgent"=dword:00000001
"PublicKeyFile"="C:\\Users\\user\\keys\\server.ppk"
"PortForwardings"="L8080=localhost:80,4L192.168.1.100:8080=localhost:80,D1080,6R[ff::2]:11=[ff::1]:12"
```

## Port Forward Bind IP Handling

The parser now supports PuTTY's bind IP specification for port forwards:

### Supported Formats:
- `L8080=localhost:80` - Local forward, any interface (uses dialog default)
- `4L192.168.1.100:8080=localhost:80` - Local forward, specific IPv4 bind
- `6L[2001:db8::1]:8080=localhost:80` - Local forward, specific IPv6 bind
- `6R[ff::2]:11=[ff::1]:12` - Remote forward, IPv6 bind to IPv6 destination
- `D1080` - Dynamic forward, any interface (uses dialog default)
- `4D192.168.1.100:1080` - Dynamic forward, specific IPv4 bind
- `6D[::1]:1080` - Dynamic forward, specific IPv6 bind

### Bind Address Priority:
1. **PuTTY bind IP** (if specified) - Takes precedence over dialog setting
2. **Dialog selection** - User-chosen default (WiFi Hotspot, localhost, or all interfaces)

### IPv6 Support:
ConnectBot fully supports IPv6 for both SSH connections and port forward bind addresses. IPv6 addresses in bind specifications are properly parsed and validated.

## Limitations Summary

- **~60% of PuTTY features** can be directly imported
- **Core SSH functionality** maps well (host, auth, port forwards)
- **Advanced SSH features** (ciphers, bugs, GSSAPI) lost
- **Terminal customization** not supported (ConnectBot uses system defaults)
- **UI preferences** not transferable to Android environment

## Success Criteria

A successful import should provide:
1. **Functional SSH connections** to all imported hosts
2. **Working port forwards** for all supported types
3. **Preserved authentication** settings where possible
4. **Clear user feedback** about unsupported features
5. **No data corruption** of existing ConnectBot configuration

The import would provide a solid foundation for basic SSH connectivity while requiring users to reconfigure advanced features specific to ConnectBot's Android environment.
