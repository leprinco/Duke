
package no.priv.garshol.duke.server;

import java.util.Properties;
import java.io.IOException;

import no.priv.garshol.duke.Logger;
import no.priv.garshol.duke.Processor;
import no.priv.garshol.duke.LinkDatabase;
import no.priv.garshol.duke.ConfigLoader;
import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.DukeException;
import no.priv.garshol.duke.DukeConfigException;
import no.priv.garshol.duke.JDBCLinkDatabase;
import no.priv.garshol.duke.JNDILinkDatabase;
import no.priv.garshol.duke.utils.ObjectUtils;
import no.priv.garshol.duke.matchers.MatchListener;
import no.priv.garshol.duke.matchers.AbstractMatchListener;
import no.priv.garshol.duke.matchers.LinkDatabaseMatchListener;

// we use this to make it easier to deal with properties
import static no.priv.garshol.duke.utils.PropertyUtils.get;

/**
 * The central class that receives notifications from the UI and timer
 * threads, controlling the actual work performed.
 */
public class DukeController extends AbstractMatchListener {
  private String status;   // what's up?
  private int records;     // number of records processed
  private long lastCheck;  // time we last checked
  private long lastRecord; // most recent time we saw a new record

  private Processor processor;
  private LinkDatabase linkdb;
  private Logger logger;
  
  public DukeController(Properties props) {
    this.status = "Initialized, inactive";
    String configfile = get(props, "duke.configfile");
    
    try {
      Configuration config = ConfigLoader.load(configfile); 
      this.processor = new Processor(config, false);
      this.linkdb = makeLinkDatabase(props);
      processor.addMatchListener(new LinkDatabaseMatchListener(config, linkdb));

      String loggerclass = get(props, "duke.logger-class", null);
      if (loggerclass != null)
        logger = (Logger) ObjectUtils.instantiate(loggerclass);
      if (logger != null)
        processor.setLogger(logger);
    } catch (Throwable e) {
      // this means init failed, and we need to clean up so that we can try
      // again later. unfortunately, we don't know what failed, so we need
      // to be careful
      if (processor != null)
        try {
          processor.close();
        } catch (Exception e2) {
          if (logger != null)
            logger.error("Couldn't close processor", e2);
        }
      if (linkdb != null)
        linkdb.close();

      throw new DukeException(e); // we failed, so signal that
    }
  }

  /**
   * Runs the record linkage process.
   */
  public void process() {
    try {
      status = "Processing";
      lastCheck = System.currentTimeMillis();

      // FIXME: how can we make the thread wait longer if there is an error?
      // FIXME: how to break off processing if we don't want to keep going?
      // FIXME: configurable batch size
      processor.deduplicate();

      status = "Sleeping";
    } catch (Throwable e) {
      status = "Thread blocked on error: " + e;
      logger.error("Error in processing; waiting", e);
    }
  }

  /**
   * Shuts down the controller, releasing all resources.
   */
  public void close() throws IOException {
    processor.close();
    linkdb.close();
  }

  public String getStatus() {
    return status;
  }

  public long getLastCheck() {
    return lastCheck;
  }

  public long getLastRecord() {
    return lastRecord;
  }

  public int getRecordCount() {
    return records;
  }

  // --- Listener implementation

  public void batchDone() {
    linkdb.commit();
  }
  
  public void endRecord() {
    records++;
    lastRecord = System.currentTimeMillis();
  }

  // --- Create link database

  private LinkDatabase makeLinkDatabase(Properties props) {
    String dbtype = get(props, "duke.linkdbtype");
    if (dbtype.equals("jdbc"))
      return makeJDBCLinkDatabase(props);
    else if (dbtype.equals("jndi"))
      return makeJNDILinkDatabase(props);
    else
      throw new DukeConfigException("Unknown link database type '" + dbtype +
                                    "'");
  }

  private LinkDatabase makeJDBCLinkDatabase(Properties props) {
    String linkjdbcuri = get(props, "duke.linkjdbcuri");
    String driverklass = get(props, "duke.jdbcdriver");
    String dbtype = get(props, "duke.database");
    String tblprefix = get(props, "duke.table-prefix", null);

    Properties jdbcprops = new Properties();
    if (get(props, "duke.username", null) != null)
      jdbcprops.put("user", get(props, "duke.username"));
    if (get(props, "duke.password", null) != null)
      jdbcprops.put("password", get(props, "duke.password"));

    JDBCLinkDatabase db;
    db = new JDBCLinkDatabase(driverklass, linkjdbcuri, dbtype, jdbcprops);
    if (tblprefix != null)
      db.setTablePrefix(tblprefix);
    return db;
  }

  private LinkDatabase makeJNDILinkDatabase(Properties props) {
    return new JNDILinkDatabase(get(props, "duke.linkjdnipath"),
                                get(props, "duke.database"));
  }
  
}