# ConnectBot Port Forwarding Bind Address Enhancement Plan

## Overview
Add support for configurable bind addresses in ConnectBot port forwarding with three options:
1. **localhost** (current behavior) - bind to 127.0.0.1
2. **0.0.0.0** - bind to all network interfaces  
3. **access_point** - bind only to WiFi access point IP (dynamically detected)

When access point binding is active, display a persistent notification showing the current AP IP address.

## Current Architecture Analysis

**Key Files:**
- `PortForwardBean.java` - Data model for port forwards
- `HostDatabase.java` - Database schema and operations
- `SSH.java` - Actual forwarding implementation (lines 648, 678 use hardcoded `InetAddress.getLocalHost()`)
- `PortForwardListActivity.java` - UI dialog management
- `dia_portforward.xml` - Port forward dialog layout

**Current Flow:**
1. User creates port forward via dialog → PortForwardBean created
2. Bean saved to database via HostDatabase
3. SSH.java reads bean and creates forwarding with hardcoded localhost binding

## Implementation Plan

### Phase 1: Data Model & Database Changes

**1.1 Update PortForwardBean**
```java
// Add new field
private String bindAddress = "localhost"; // default for backward compatibility

// Add getter/setter
public String getBindAddress() { return bindAddress; }
public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

// Update constructor to include bindAddress parameter
// Update getValues() to include new field
```

**1.2 Update HostDatabase**
```java
// Add new database field constant
public final static String FIELD_PORTFORWARD_BINDADDR = "bindaddr";

// Update database schema (add migration for version bump)
// Default value: "localhost" for backward compatibility
```

**1.3 Database Migration**
- Increment database version
- Add migration code to add `bindaddr` column with default "localhost"
- Update all SQL queries to include new field

### Phase 2: Network Utilities

**2.1 Create NetworkUtils class**
```java
public class NetworkUtils {
    public static String getAccessPointIP(Context context)
    public static boolean isAccessPointAvailable(Context context)  
    public static void registerNetworkChangeListener(Context context, NetworkChangeListener listener)
    
    interface NetworkChangeListener {
        void onAccessPointIPChanged(String newIP);
        void onAccessPointDisconnected();
    }
}
```

**2.2 Network Monitoring Service**
- Create background service to monitor WiFi state changes
- Update access point IP when network changes
- Notify active port forwards when AP IP changes

### Phase 3: UI Changes

**3.1 Update dia_portforward.xml**
```xml
<!-- Add after destination row -->
<TableRow>
    <TextView android:text="@string/prompt_bind_address" />
    <RadioGroup android:id="@+id/bind_address_group">
        <RadioButton android:id="@+id/bind_localhost" android:text="@string/bind_localhost" />
        <RadioButton android:id="@+id/bind_all_interfaces" android:text="@string/bind_all_interfaces" />  
        <RadioButton android:id="@+id/bind_access_point" android:text="@string/bind_access_point" />
    </RadioGroup>
</TableRow>

<!-- Add security warning text -->
<TableRow android:id="@+id/security_warning_row" android:visibility="gone">
    <TextView android:text="@string/security_warning_network_exposure" />
</TableRow>
```

**3.2 Update PortForwardListActivity**
- Add bind address RadioGroup handling
- Show/hide security warning based on selection
- Pass bind address to PortForwardBean constructor
- Update existing port forward editing dialog

**3.3 Update Port Forward Display**
- Modify PortForwardBean.getDescription() to include bind address info
- Update list item layout to show bind type icon/indicator

### Phase 4: Core Logic Changes

**4.1 Update SSH.java**
```java
// Replace hardcoded InetAddress.getLocalHost() with:
private InetAddress resolveBindAddress(String bindAddress, Context context) {
    switch(bindAddress) {
        case "localhost":
            return InetAddress.getLoopbackAddress();
        case "0.0.0.0":  
            return InetAddress.getByName("0.0.0.0");
        case "access_point":
            String apIP = NetworkUtils.getAccessPointIP(context);
            return apIP != null ? InetAddress.getByName(apIP) : InetAddress.getLoopbackAddress();
        default:
            return InetAddress.getLoopbackAddress();
    }
}

// Update enablePortForward() method:
InetAddress bindAddr = resolveBindAddress(portForward.getBindAddress(), context);
new InetSocketAddress(bindAddr, portForward.getSourcePort())
```

**4.2 Error Handling**
- Handle cases where access point IP is not available
- Fallback to localhost if AP binding fails
- Log appropriate error messages

### Phase 5: Notification System

**5.1 AccessPointNotificationManager**
```java
public class AccessPointNotificationManager {
    public void showAccessPointNotification(String apIP)
    public void updateAccessPointNotification(String newIP)  
    public void hideAccessPointNotification()
    public boolean hasActiveAccessPointForwards()
}
```

**5.2 Integration**
- Show notification when first AP port forward is enabled
- Update notification when AP IP changes
- Hide notification when last AP port forward is disabled
- Persistent notification with current AP IP address

### Phase 6: String Resources

**6.1 Add new strings**
```xml
<string name="prompt_bind_address">Bind to:</string>
<string name="bind_localhost">Localhost only</string>
<string name="bind_all_interfaces">All interfaces (0.0.0.0)</string>
<string name="bind_access_point">WiFi access point only</string>
<string name="security_warning_network_exposure">⚠️ This will expose the forwarded port to other devices on the network</string>
<string name="notification_access_point_title">Port forwarding active</string>
<string name="notification_access_point_text">Access point IP: %s</string>
```

## Implementation Strategy

### Minimal Changes Approach
1. **Backward Compatibility**: Default bind address to "localhost", existing port forwards continue working
2. **Database Migration**: Clean migration that doesn't break existing data
3. **UI Integration**: Extend existing dialog rather than creating new UI
4. **Code Patterns**: Follow existing patterns in PortForwardBean, HostDatabase, etc.
5. **Error Handling**: Graceful fallbacks when network detection fails

### Security Considerations
1. **Clear Warnings**: Prominent security warnings for network-exposed bindings
2. **User Consent**: Explicit user choice for each binding mode
3. **Logging**: Security-relevant events logged appropriately
4. **Documentation**: Clear documentation of security implications

### Testing Strategy
1. **Backward Compatibility**: Verify existing port forwards still work
2. **All Binding Modes**: Test localhost, 0.0.0.0, and access point bindings
3. **Network Changes**: Test AP IP changes, WiFi disconnect/reconnect
4. **Notification System**: Verify notification behavior
5. **Edge Cases**: Test with no WiFi, multiple networks, etc.

## Implementation Order
1. Database schema changes and migration
2. PortForwardBean updates  
3. NetworkUtils implementation
4. UI dialog updates
5. SSH.java binding logic updates
6. Notification system
7. Testing and refinement

This plan ensures the feature can be cleanly implemented with minimal disruption to existing code, making it suitable for upstream contribution.