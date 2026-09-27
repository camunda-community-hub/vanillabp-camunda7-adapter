package io.vanillabp.camunda7.springboot.webapps;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This module's OVERLAY of the shared <code>vanillabp.*</code> configuration tree: the
 * webapps are configured per adapter id, at
 * <code>vanillabp.adapters.&lt;id&gt;.webapps.*</code>, like every other setting of an
 * adapter instance. A second {@code @ConfigurationProperties} class over the same prefix
 * coexists with the platform's binding of the core model and with the adapter's engine
 * overlay; keys unknown to a view are ignored by the JavaBean binding.
 * <p>
 * The adapter-id set is NEVER derived from this map. It comes from the engines VanillaBP
 * actually built, and this overlay is a per-known-id lookup only.
 */
@ConfigurationProperties("vanillabp")
public class Camunda7WebappsProperties {

  /**
   * Spring Boot builds one per section it finds and fills it through the setters.
   */
  public Camunda7WebappsProperties() {

  }

  /** The adapter sections of the shared tree, keyed by adapter id. */
  private Map<String, AdapterSection> adapters = Map.of();

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @return The sections found, empty where the application configured none
   */
  public Map<String, AdapterSection> getAdapters() {
    return adapters;
  }

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @param adapters The sections Spring Boot bound
   */
  public void setAdapters(
      final Map<String, AdapterSection> adapters) {
    this.adapters = adapters;
  }

  /**
   * The webapp settings of an adapter id, defaults if the section is absent.
   *
   * @param adapterId The adapter id
   * @return The settings (never <code>null</code>)
   */
  public Webapps webappsOf(
      final String adapterId) {

    final var section = adapters.get(adapterId);
    return (section == null) || (section.getWebapps() == null)
        ? new Webapps()
        : section.getWebapps();

  }

  /** One <code>vanillabp.adapters.&lt;id&gt;</code> section, webapp keys only. */
  public static class AdapterSection {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public AdapterSection() {

    }

    /** The <code>webapps</code> section of this adapter id. */
    private Webapps webapps;

    /**
     * The <code>webapps</code> section of this adapter id.
     *
     * @return The section, <code>null</code> where this adapter id has none
     */
    public Webapps getWebapps() {
      return webapps;
    }

    /**
     * The <code>webapps</code> section of this adapter id.
     *
     * @param webapps The section Spring Boot bound
     */
    public void setWebapps(
        final Webapps webapps) {
      this.webapps = webapps;
    }

  }

  /** The <code>webapps</code> section of an adapter id. */
  public static class Webapps {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Webapps() {

    }

    /**
     * Whether Cockpit, Tasklist and Admin serve this engine. On by default: an
     * application which added this module wants the webapps, and switching them off per
     * adapter id is the exception (e.g. an engine that only exists for a migration).
     */
    private boolean enabled = true;

    /**
     * The administrator to create on startup, if any. Without it this module creates no
     * user, and the webapps show their setup wizard - which is what an application
     * managing its own users wants.
     */
    private AdminUser adminUser;

    /**
     * Whether Cockpit, Tasklist and Admin serve this engine.
     *
     * @return Whether the webapps are served, <code>true</code> unless switched off
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Whether Cockpit, Tasklist and Admin serve this engine.
     *
     * @param enabled Whether the webapps are served
     */
    public void setEnabled(
        final boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * The administrator to create on startup.
     *
     * @return The user to create, <code>null</code> where this module creates none
     */
    public AdminUser getAdminUser() {
      return adminUser;
    }

    /**
     * The administrator to create on startup.
     *
     * @param adminUser The user to create
     */
    public void setAdminUser(
        final AdminUser adminUser) {
      this.adminUser = adminUser;
    }

  }

  /** The administrator created on startup. */
  public static class AdminUser {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public AdminUser() {

    }

    /** The user id used to log in. */
    private String id;

    /** The password. It is never logged, and never part of a message. */
    private String password;

    /** The first name shown next to the user. */
    private String firstName;

    /** The last name shown next to the user. */
    private String lastName;

    /** The mail address of the user. */
    private String email;

    /**
     * The user id used to log in.
     *
     * @return The user id
     */
    public String getId() {
      return id;
    }

    /**
     * The user id used to log in.
     *
     * @param id The user id
     */
    public void setId(
        final String id) {
      this.id = id;
    }

    /**
     * The password. It is never logged, and never part of a message.
     *
     * @return The password
     */
    public String getPassword() {
      return password;
    }

    /**
     * The password. It is never logged, and never part of a message.
     *
     * @param password The password
     */
    public void setPassword(
        final String password) {
      this.password = password;
    }

    /**
     * The first name shown next to the user.
     *
     * @return The first name
     */
    public String getFirstName() {
      return firstName;
    }

    /**
     * The first name shown next to the user.
     *
     * @param firstName The first name
     */
    public void setFirstName(
        final String firstName) {
      this.firstName = firstName;
    }

    /**
     * The last name shown next to the user.
     *
     * @return The last name
     */
    public String getLastName() {
      return lastName;
    }

    /**
     * The last name shown next to the user.
     *
     * @param lastName The last name
     */
    public void setLastName(
        final String lastName) {
      this.lastName = lastName;
    }

    /**
     * The mail address of the user.
     *
     * @return The mail address
     */
    public String getEmail() {
      return email;
    }

    /**
     * The mail address of the user.
     *
     * @param email The mail address
     */
    public void setEmail(
        final String email) {
      this.email = email;
    }

  }

}
