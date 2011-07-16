/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package  org.jasig.portal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.portal.groups.IEntityGroup;
import org.jasig.portal.groups.IGroupMember;
import org.jasig.portal.groups.ILockableEntityGroup;
import org.jasig.portal.layout.dao.IStylesheetUserPreferencesDao;
import org.jasig.portal.portlet.dao.IPortletEntityDao;
import org.jasig.portal.properties.PropertiesManager;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.security.PersonFactory;
import org.jasig.portal.services.GroupService;
import org.jasig.portal.services.SequenceGenerator;
import org.jasig.portal.utils.ConcurrentMapUtils;
import org.jasig.portal.utils.CounterStoreFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.collect.MapMaker;

/**
 * SQL implementation for managing creation and removal of User Portal Data
 * @author Susan Bramhall, Yale University (modify by Julien Marchal, University Nancy 2; Eric Dalquist - edalquist@unicon.net)
 * @version $Revision$
 */
@Service
public class RDBMUserIdentityStore  implements IUserIdentityStore {

    private static final Log log = LogFactory.getLog(RDBMUserIdentityStore.class);
    private static String PROFILE_TABLE = "UP_USER_PROFILE";

  //*********************************************************************
  // Constants
    private static final String defaultTemplateUserName = PropertiesManager.getProperty("org.jasig.portal.services.Authentication.defaultTemplateUserName");
    private static final String templateAttrName = "uPortalTemplateUserName";
    static int DEBUG = 0;
    private static final ConcurrentMap<String, Object> userLocks = new MapMaker()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .makeMap();
    
    private static Object getLock(IPerson person) {
        final String username = (String)person.getAttribute(IPerson.USERNAME);
        
        final Object lock = userLocks.get(username);
        if (lock != null) {
            return lock;
        }
        
        return ConcurrentMapUtils.putIfAbsent(userLocks, username, new Object());
    }
    
    private JdbcOperations jdbcOperations;
    private TransactionOperations transactionOperations;
    
    @Autowired
    public void setPlatformTransactionManager(@Qualifier("PortalDb") PlatformTransactionManager platformTransactionManager) {
        this.transactionOperations = new TransactionTemplate(platformTransactionManager);
    }

    @javax.annotation.Resource(name="PortalDb")
    public void setDataSource(DataSource dataSource) {
        this.jdbcOperations = new JdbcTemplate(dataSource);
    }
    
 /**
   * getuPortalUID -  return a unique uPortal key for a user.
   *    calls alternate signature with createPortalData set to false.
   * @param person the person object
   * @return uPortalUID number
   * @throws Exception if no user is found.
   */
  public int getPortalUID (IPerson person) throws Exception {
    int uPortalUID=-1;
    uPortalUID=this.getPortalUID(person, false);
    return uPortalUID;
    }

  /**
   *
   * removeuPortalUID
   * @param   uPortalUID integer key to uPortal data for a user
   * @throws SQLException exception if a sql error is encountered
   */
  public void removePortalUID(final int uPortalUID) throws Exception {
      
      this.transactionOperations.execute(new TransactionCallback<Object>() {
          @Override
          public Object doInTransaction(TransactionStatus status) {
              return jdbcOperations.execute(new ConnectionCallback<Object>() {
                  @Override
                  public Object doInConnection(Connection con) throws SQLException, DataAccessException {
    
      java.sql.PreparedStatement ps = null;
      Statement stmt = null;
      ResultSet rs = null;
      
      // TODO get these working
//      portletEntityDao.deletePortletEntitiesForUser(uPortalUID);
//      stylesheetUserPreferencesDao.deleteStylesheetUserPreferencesForUser(uPortalUID)


      // START of Addition after bug declaration (bug id 1516)
      // Permissions delete
      // must be made before delete user in UP_USER
      rs = stmt.executeQuery("SELECT USER_NAME FROM UP_USER WHERE USER_ID="+uPortalUID);
      String name = "";
      if ( rs.next() )
        name = rs.getString(1);
      rs.close();
      
      if (PersonFactory.GUEST_USERNAME.equals(name)) {
          throw new IllegalArgumentException("CANNOT RESET LAYOUT FOR A GUEST USER: " + uPortalUID);
      }
      
      rs = stmt.executeQuery("SELECT ENTITY_TYPE_ID FROM UP_ENTITY_TYPE WHERE ENTITY_TYPE_NAME = 'org.jasig.portal.security.IPerson'");
      int type = -1;
      if ( rs.next() )
        type = rs.getInt(1);
      rs.close();
      rs = null;
      String SQLDelete = "DELETE FROM UP_PERMISSION WHERE PRINCIPAL_KEY='"+name+"' AND PRINCIPAL_TYPE="+type;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      rs = stmt.executeQuery("SELECT M.GROUP_ID " +
			"FROM UP_GROUP_MEMBERSHIP M, UP_GROUP G, UP_ENTITY_TYPE E " +
			"WHERE M.GROUP_ID = G.GROUP_ID " +
			"  AND G.ENTITY_TYPE_ID = E.ENTITY_TYPE_ID " +
			"  AND  E.ENTITY_TYPE_NAME = 'org.jasig.portal.security.IPerson'" +
			"  AND  M.MEMBER_KEY ='"+name+"' AND  M.MEMBER_IS_GROUP = 'F'");
      java.util.Vector groups = new java.util.Vector();
      while ( rs.next() )
        groups.add(rs.getString(1));
      rs.close();
      rs = null;

      // Remove from local group
      // Delete from DeleteUser.java and place here
      // must be made before delete user in UP_USER
      ps = con.prepareStatement("DELETE FROM UP_GROUP_MEMBERSHIP WHERE MEMBER_KEY='"+name+"' AND GROUP_ID=?");
      for ( int i = 0; i < groups.size(); i++ ) {
        String group = (String) groups.get(i);
        ps.setString(1,group);
        ps.executeUpdate();
      }
      if ( ps != null ) ps.close();
      // END of Addition after bug declaration (bug id 1516)

      SQLDelete = "DELETE FROM UP_USER WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_LAYOUT  WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_PARAM WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_PROFILE  WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_LAYOUT    WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_LAYOUT_PARAM WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_UA_MAP WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_LAYOUT_STRUCT  WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      // START of Addition after bug declaration (bug id 1516)
      SQLDelete = "DELETE FROM UP_USER_LOCALE WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_PROFILE_MDATA WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_PROFILE_LOCALE WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_USER_LAYOUT_MDATA WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);

      SQLDelete = "DELETE FROM UP_LAYOUT_STRUCT_MDATA  WHERE USER_ID = " + uPortalUID;
      if (log.isDebugEnabled())
          log.debug("RDBMUserIdentityStore::removePortalUID(): " + SQLDelete);
      stmt.executeUpdate(SQLDelete);
      // END of Addition after bug declaration (bug id 1516)

      return null;
                  }
              });
          }
      });
    }

   /**
    * Return the username to be used for authorization (exit hook)
    * @param person
    * @return usernmae
    */
   public String getUsername(IPerson person) {
	   return (String)person.getAttribute(IPerson.USERNAME);
   }

   /**
    * Get the portal user ID for this person object.
    * @param person
    * @param createPortalData indicating whether to try to create all uPortal data for this user from template prototype
    * @return uPortalUID number or -1 if unable to create user.
    * @throws AuthorizationException if createPortalData is false and no user is found
    *  or if a sql error is encountered
    */
   public int getPortalUID (IPerson person, boolean createPortalData) throws AuthorizationException {
       int uid;
       String username = (String)person.getAttribute(IPerson.USERNAME);

       // only synchronize a non-guest request.
       if (PersonFactory.GUEST_USERNAME.equals(username)) {
           uid = __getPortalUID(person, createPortalData);
       } else {
           Object lock = getLock(person);
           synchronized (lock) {
               uid = __getPortalUID(person, createPortalData);
           }
       }
       return uid;
   }

   /* (non-javadoc)
    * @see org.jasig.portal.IUserIdentityStore#getPortalUserName(int)
    */
   public String getPortalUserName(final int uPortalUID) {
       final DataSource dataSource = RDBMServices.getDataSource();
       final SimpleJdbcTemplate simpleJdbcTemplate = new SimpleJdbcTemplate(dataSource);

       final List<String> results = simpleJdbcTemplate.query("SELECT USER_NAME FROM UP_USER WHERE USER_ID=?", this.userNameRowMapper, uPortalUID);
       final String userName = (String)DataAccessUtils.singleResult(results);
       return userName;
   }
   
   private final UserNameRowMapper userNameRowMapper = new UserNameRowMapper();
   private class UserNameRowMapper implements ParameterizedRowMapper<String> {
       public String mapRow(ResultSet rs, int rowNum) throws SQLException {
           return rs.getString("USER_NAME");
       }
   }

   private int __getPortalUID (IPerson person, boolean createPortalData) throws AuthorizationException {
       PortalUser portalUser = null;

       try {
           String userName = getUsername(person);
           String templateName = getTemplateName(person);
           portalUser = getPortalUser(userName);

           if (createPortalData) {
               //If we are allowed to modify the database

               if (portalUser != null) {
                   //If the user has logged in we may have to update their template user information

                   boolean hasSavedLayout = userHasSavedLayout(portalUser.getUserId());
                   if (!hasSavedLayout) {

                       TemplateUser templateUser = getTemplateUser(templateName);
                       if (portalUser.getDefaultUserId() != templateUser.getUserId()) {
                           //Update user data with new template user's data
                           updateUser(portalUser.getUserId(), person, templateUser);
                       }
                   }
               }
               else {
                   //User hasn't logged in before, some data needs to be created for them based on their template user

                   // Retrieve the information for the template user
                   TemplateUser templateUser = getTemplateUser(templateName);
                   if (templateUser == null) {
                       throw new AuthorizationException("No information found for template user = " + templateName + ". Cannot create new account for " + userName);
                   }

                   // Get a new user ID for this user
                   int newUID = getNewPortalUID(person);

                   // Add new user to all appropriate tables
                   int newPortalUID = addNewUser(newUID, person, templateUser);
                   portalUser = new PortalUser();
                   portalUser.setUserId(newPortalUID);
               }
           }
           else if (portalUser == null) {
               //If this is a new user and we can't create them
               throw new AuthorizationException("No portal information exists for user " + userName);
           }

       }
       catch (AuthorizationException e) {
           throw e;
       }
       catch (RuntimeException e) {
           throw e;
       }
       catch (Exception e) {
           throw new RuntimeException(e);
       }

       return portalUser.getUserId();
   }
  
  protected int getNewPortalUID(IPerson person) throws Exception {
	return CounterStoreFactory.getCounterStoreImpl().getIncrementIntegerId("UP_USER");
  }

  static final protected void commit (Connection connection) {
    try {
      if (RDBMServices.getDbMetaData().supportsTransactions())
        connection.commit();
    } catch (Exception e) {
      log.error( "RDBMUserIdentityStore::commit(): " + e);
    }
  }

  static final protected void rollback (Connection connection) {
    try {
      if (RDBMServices.getDbMetaData().supportsTransactions())
        connection.rollback();
    } catch (Exception e) {
      log.error( "RDBMUserIdentityStore::rollback(): " + e);
    }
  }

  /**
   * Gets the PortalUser data store object for the specified user name.
   *
   * @param userName The user's name
   * @return A PortalUser object or null if the user doesn't exist.
   * @throws Exception
   */
  protected PortalUser getPortalUser(final String userName) throws Exception {
      return jdbcOperations.execute(new ConnectionCallback<PortalUser>() {
          @Override
          public PortalUser doInConnection(Connection con) throws SQLException, DataAccessException {

          PortalUser portalUser = null;
          PreparedStatement pstmt = null;

          try {
              String query = "SELECT USER_ID, USER_DFLT_USR_ID FROM UP_USER WHERE USER_NAME=?";

              pstmt = con.prepareStatement(query);
              pstmt.setString(1, userName);

              ResultSet rs = null;
              try {
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::getPortalUID(userName=" + userName + "): " + query);
                  rs = pstmt.executeQuery();
                  if (rs.next()) {
                      portalUser = new PortalUser();
                      portalUser.setUserId(rs.getInt("USER_ID"));
                      portalUser.setUserName(userName);
                      portalUser.setDefaultUserId(rs.getInt("USER_DFLT_USR_ID"));
                  }
              } finally {
                  try { rs.close(); } catch (Exception e) {}
              }
          } finally {
              try { pstmt.close(); } catch (Exception e) {}
          }
          
          return portalUser;
          }
      });
  }

  protected String getTemplateName(IPerson person) {
      String templateName = (String)person.getAttribute(templateAttrName);
      // Just use the default template if requested template not populated
      if (templateName == null || templateName.equals("")) {
          templateName = defaultTemplateUserName;
      }
      return templateName;
  }

  /**
   * Gets the TemplateUser data store object for the specified template user name.
   *
   * @param templateUserName The template user's name
   * @return A TemplateUser object or null if the user doesn't exist.
   * @throws Exception
   */
  protected TemplateUser getTemplateUser(final String templateUserName) throws Exception {
      return jdbcOperations.execute(new ConnectionCallback<TemplateUser>() {
          @Override
          public TemplateUser doInConnection(Connection con) throws SQLException, DataAccessException {

          TemplateUser templateUser = null;
          PreparedStatement pstmt = null;
          try {
              String query = "SELECT USER_ID, USER_DFLT_LAY_ID FROM UP_USER WHERE USER_NAME=?";

              pstmt = con.prepareStatement(query);
              pstmt.setString(1, templateUserName);

              ResultSet rs = null;
              try {
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::getTemplateUser(templateUserName=" + templateUserName + "): " + query);
                  rs = pstmt.executeQuery();
                  if (rs.next()) {
                      templateUser = new TemplateUser();
                      templateUser.setUserName(templateUserName);
                      templateUser.setUserId(rs.getInt("USER_ID"));
                      templateUser.setDefaultLayoutId(rs.getInt("USER_DFLT_LAY_ID"));
                  } else {
                      if (!templateUserName.equals(defaultTemplateUserName)) {
                          try {
                            templateUser = getTemplateUser(defaultTemplateUserName);
                        }
                        catch (Exception e) {
                            throw new SQLException(e);
                        }
                      }
                  }
              } finally {
                  try { rs.close(); } catch (Exception e) {}
              }
          } finally {
              try { pstmt.close(); } catch (Exception e) {}
          }
          return templateUser;
          }
      });
  }

  protected boolean userHasSavedLayout(final int userId) throws Exception {
      return jdbcOperations.execute(new ConnectionCallback<Boolean>() {
          @Override
          public Boolean doInConnection(Connection con) throws SQLException, DataAccessException {

          boolean userHasSavedLayout = false;
          PreparedStatement pstmt = null;
          try {
              String query = "SELECT * FROM UP_USER_PROFILE WHERE USER_ID=? AND LAYOUT_ID IS NOT NULL AND LAYOUT_ID!=0";

              pstmt = con.prepareStatement(query);
              pstmt.setInt(1, userId);

              ResultSet rs = null;
              try {
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::getTemplateUser(userId=" + userId + "): " + query);
                  rs = pstmt.executeQuery();
                  if (rs.next()) {
                      userHasSavedLayout = true;
                  }
              } finally {
                  try { rs.close(); } catch (Exception e) {}
              }
          } finally {
              try { pstmt.close(); } catch (Exception e) {}
          }
          return userHasSavedLayout;
          
          }
      });
  }
  
  private ILockableEntityGroup getSafeLockableGroup(IEntityGroup eg, IGroupMember gm) {
      if (log.isTraceEnabled()) {
          log.trace("Creating lockable group for group/member: " + eg + "/" + gm);
      }
      
      ILockableEntityGroup leg = null;
      
      try {
          if (eg.isEditable()) {
	          leg = GroupService.findLockableGroup(eg.getKey(), gm.getKey());
          }
      } catch (Exception e) {
          // Bummer.  but the only thing to do is to press on
          log.error("Unable to create lockable group for group/member: " + eg + "/" + gm, e);
      }
      
      return leg;
  }
  
  /**
   * Remove a person from a group.  This method catches and logs exceptions
   * exceptions encountered performing the removal.
   * @param person person to be removed (used for logging)
   * @param me member representing the person
   * @param eg group from which the user should be removed
   */
  private void removePersonFromGroup(IPerson person, IGroupMember me, IEntityGroup eg) {
      if (log.isTraceEnabled()) {
          log.trace("Removing " + person + " from group " + eg);
      }
      try {
          if (eg.isEditable()) {
              eg.removeMember(me);
              eg.updateMembers();
          }
      } catch (Exception e) {
          // Bummer.  but the only thing to do is to press on
          log.error("Unable to remove " + person + " from group " + eg, e);
      }
  }
  
  /**
   * Add a person to a group. This method catches and logs exceptions encountered
   * performing the removal.
   * @param person person to be added (used for logging)
   * @param me member representing the person
   * @param eg group to which the user should be added
   */
  private void addPersonToGroup(IPerson person, IGroupMember me, IEntityGroup eg) {
      if (log.isTraceEnabled()) {
          log.trace("Adding " + person + " to group " + eg);
      }
      try {
          if (eg.isEditable()) {
              eg.addMember(me);
              eg.updateMembers();
          }
      } catch (Exception e) {
          log.error("Unable to add " + person + " to group " + eg, e);
      }
  }

  protected void updateUser(final int userId, final IPerson person, final TemplateUser templateUser) throws Exception {
      // Remove my existing group memberships
      IGroupMember me = GroupService.getGroupMember(person.getEntityIdentifier());
      Iterator myExistingGroups = me.getContainingGroups();
      while (myExistingGroups.hasNext()) {
          IEntityGroup eg = (IEntityGroup)myExistingGroups.next();
          ILockableEntityGroup leg = getSafeLockableGroup(eg, me);
          if (leg != null) {
              removePersonFromGroup(person, me, leg);
          }
      }

      // Copy template user's groups memberships
      IGroupMember template = GroupService.getEntity(templateUser.getUserName(), Class.forName("org.jasig.portal.security.IPerson"));
      Iterator templateGroups = template.getContainingGroups();
      while (templateGroups.hasNext()) {
          IEntityGroup eg = (IEntityGroup)templateGroups.next();
          ILockableEntityGroup leg = getSafeLockableGroup(eg, me);
          if (leg != null) {
              addPersonToGroup(person, me, leg);
          }
      }

      this.transactionOperations.execute(new TransactionCallback<Object>() {
          @Override
          public Object doInTransaction(TransactionStatus status) {
              return jdbcOperations.execute(new ConnectionCallback<Object>() {
                  @Override
                  public Object doInConnection(Connection con) throws SQLException, DataAccessException {

          PreparedStatement deleteStmt = null;
          PreparedStatement queryStmt = null;
          PreparedStatement insertStmt = null;
          try {
              // Update UP_USER
              String update =
                  "UPDATE UP_USER " +
                  "SET USER_DFLT_USR_ID=?, " +
                      "USER_DFLT_LAY_ID=?, " +
                      "NEXT_STRUCT_ID=null " +
                  "WHERE USER_ID=?";

              insertStmt = con.prepareStatement(update);
              insertStmt.setInt(1, templateUser.getUserId());
              insertStmt.setInt(2, templateUser.getDefaultLayoutId());
              insertStmt.setInt(3, userId);

              if (log.isDebugEnabled())
                  log.debug("RDBMUserIdentityStore::addNewUser(): " + update);
              insertStmt.executeUpdate();
              insertStmt.close();

              // Start copying...
              ResultSet rs = null;
              String delete = null;
              String query = null;
              String insert = null;
              try {
                  // Update UP_USER_PARAM
                  delete =
                      "DELETE FROM UP_USER_PARAM " +
                      "WHERE USER_ID=?";
                  deleteStmt = con.prepareStatement(delete);
                  deleteStmt.setInt(1, userId);
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + userId + "): " + delete);
                  deleteStmt.executeUpdate();
                  deleteStmt.close();

                  query =
                      "SELECT USER_ID, USER_PARAM_NAME, USER_PARAM_VALUE " +
                      "FROM UP_USER_PARAM " +
                      "WHERE USER_ID=?";
                  queryStmt = con.prepareStatement(query);
                  queryStmt.setInt(1, templateUser.getUserId());
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + templateUser.getUserId() + "): " + query);
                  rs = queryStmt.executeQuery();

                  insert =
                      "INSERT INTO UP_USER_PARAM (USER_ID, USER_PARAM_NAME, USER_PARAM_VALUE) " +
                      "VALUES(?, ?, ?)";
                  insertStmt = con.prepareStatement(insert);
                  while (rs.next()) {

                      String userParamName = rs.getString("USER_PARAM_NAME");
                      String userParamValue = rs.getString("USER_PARAM_VALUE");

                      insertStmt.setInt(1, userId);
                      insertStmt.setString(2, userParamName);
                      insertStmt.setString(3, userParamValue);

                      if (log.isDebugEnabled())
                          log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + userId + ", USER_PARAM_NAME=" + userParamName + ", USER_PARAM_VALUE=" + userParamValue + "): " + insert);
                      insertStmt.executeUpdate();
                  }
                  rs.close();
                  queryStmt.close();
                  insertStmt.close();


                  // Update UP_USER_PROFILE
                  delete =
                      "DELETE FROM UP_USER_PROFILE " +
                      "WHERE USER_ID=?";
                  deleteStmt = con.prepareStatement(delete);
                  deleteStmt.setInt(1, userId);
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + userId + "): " + delete);
                  deleteStmt.executeUpdate();
                  deleteStmt.close();

                  query =
                      "SELECT USER_ID, PROFILE_FNAME, PROFILE_NAME, DESCRIPTION, " +
                      "STRUCTURE_SS_ID, THEME_SS_ID " +
                      "FROM UP_USER_PROFILE " +
                      "WHERE USER_ID=?";
                  queryStmt = con.prepareStatement(query);
                  queryStmt.setInt(1, templateUser.getUserId());
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + templateUser.getUserId() + "): " + query);
                  rs = queryStmt.executeQuery();

                  insert =
                      "INSERT INTO UP_USER_PROFILE (USER_ID, PROFILE_ID, PROFILE_FNAME, PROFILE_NAME, DESCRIPTION, LAYOUT_ID, STRUCTURE_SS_ID, THEME_SS_ID) " +
                      "VALUES(?, ?, ?, ?, ?, NULL, ?, ?)";
                  insertStmt = con.prepareStatement(insert);
                  while (rs.next()) {
                	  int id = getNextKey();

                      String profileFname = rs.getString("PROFILE_FNAME");
                      String profileName = rs.getString("PROFILE_NAME");
                      String description = rs.getString("DESCRIPTION");
                      int structure = rs.getInt("STRUCTURE_SS_ID");
                      int theme = rs.getInt("THEME_SS_ID");

                      insertStmt.setInt(1, userId);
                      insertStmt.setInt(2, id);
                      insertStmt.setString(3, profileFname);
                      insertStmt.setString(4, profileName);
                      insertStmt.setString(5, description);
                      insertStmt.setInt(6, structure);
                      insertStmt.setInt(7, theme);

                      if (log.isDebugEnabled())
                          log.debug("RDBMUserIdentityStore::updateUser(USER_ID=" + userId + ", PROFILE_FNAME=" + profileFname + ", PROFILE_NAME=" + profileName + ", DESCRIPTION=" + description + "): " + insert);
                      insertStmt.executeUpdate();
                  }
                  rs.close();
                  queryStmt.close();
                  insertStmt.close();

                  // If we made it all the way though, commit the transaction
                  if (RDBMServices.getDbMetaData().supportsTransactions())
                      con.commit();

              }
              finally {
                  try { rs.close(); } catch (Exception e) {}
              }
          }
          finally {
              try { deleteStmt.close(); } catch (Exception e) {}
              try { queryStmt.close(); } catch (Exception e) {}
              try { insertStmt.close(); } catch (Exception e) {}
          }
          
          return null;
                  }
              });
          }
      });
  }

  protected int addNewUser(final int newUID, final IPerson person, final TemplateUser templateUser) throws Exception {
      // Copy template user's groups memberships
      IGroupMember me = GroupService.getGroupMember(person.getEntityIdentifier());
      IGroupMember template = GroupService.getEntity(templateUser.getUserName(), Class.forName("org.jasig.portal.security.IPerson"));
      Iterator templateGroups = template.getContainingGroups();
      while (templateGroups.hasNext()) {
          IEntityGroup eg = (IEntityGroup)templateGroups.next();
          ILockableEntityGroup leg = getSafeLockableGroup(eg, me);
          if (leg != null) {
              addPersonToGroup(person, me, leg);
          }
      }

      return this.transactionOperations.execute(new TransactionCallback<Integer>() {
          @Override
          public Integer doInTransaction(TransactionStatus status) {
              return jdbcOperations.execute(new ConnectionCallback<Integer>() {
                  @Override
                  public Integer doInConnection(Connection con) throws SQLException, DataAccessException {

          int uPortalUID = -1;
          PreparedStatement queryStmt = null;
          PreparedStatement insertStmt = null;
          try {
              // Add to UP_USER
              String insert =
                  "INSERT INTO UP_USER (USER_ID, USER_NAME, USER_DFLT_USR_ID, USER_DFLT_LAY_ID, NEXT_STRUCT_ID, LST_CHAN_UPDT_DT)" +
                  "VALUES (?, ?, ?, ?, null, null)";

              String userName = getUsername(person);

              insertStmt = con.prepareStatement(insert);
              insertStmt.setInt(1, newUID);
              insertStmt.setString(2, userName);
              insertStmt.setInt(3, templateUser.getUserId());
              insertStmt.setInt(4, templateUser.getDefaultLayoutId());

              if (log.isDebugEnabled())
                  log.debug("RDBMUserIdentityStore::addNewUser(USER_ID=" + newUID + ", USER_NAME=" + userName + ", USER_DFLT_USR_ID=" + templateUser.getUserId() + ", USER_DFLT_LAY_ID=" + templateUser.getDefaultLayoutId() + "): " + insert);
              insertStmt.executeUpdate();
              insertStmt.close();
              insertStmt = null;


              // Start copying...
              ResultSet rs = null;
              String query = null;
              try {
                  // Add to UP_USER_PARAM
                  query =
                      "SELECT USER_ID, USER_PARAM_NAME, USER_PARAM_VALUE " +
                      "FROM UP_USER_PARAM " +
                      "WHERE USER_ID=?";
                  queryStmt = con.prepareStatement(query);
                  queryStmt.setInt(1, templateUser.getUserId());
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::addNewUser(USER_ID=" + templateUser.getUserId() + "): " + query);
                  rs = queryStmt.executeQuery();

                  insert =
                      "INSERT INTO UP_USER_PARAM (USER_ID, USER_PARAM_NAME, USER_PARAM_VALUE) " +
                      "VALUES(?, ?, ?)";
                  insertStmt = con.prepareStatement(insert);
                  while (rs.next()) {
                      String userParamName = rs.getString("USER_PARAM_NAME");
                      String userParamValue = rs.getString("USER_PARAM_VALUE");

                      insertStmt.setInt(1, newUID);
                      insertStmt.setString(2, userParamName);
                      insertStmt.setString(3, userParamValue);

                      if (log.isDebugEnabled())
                          log.debug("RDBMUserIdentityStore::addNewUser(USER_ID=" + newUID + ", USER_PARAM_NAME=" + userParamName + ", USER_PARAM_VALUE=" + userParamValue + "): " + insert);
                      insertStmt.executeUpdate();
                  }
                  rs.close();
                  queryStmt.close();

                  if (insertStmt != null) {
                    insertStmt.close();
                    insertStmt = null;
                  }


                  // Add to UP_USER_PROFILE
                  query =
                      "SELECT USER_ID, PROFILE_FNAME, PROFILE_NAME, DESCRIPTION, " +
                      "STRUCTURE_SS_ID, THEME_SS_ID " +
                      "FROM UP_USER_PROFILE " +
                      "WHERE USER_ID=?";
                  queryStmt = con.prepareStatement(query);
                  queryStmt.setInt(1, templateUser.getUserId());
                  if (log.isDebugEnabled())
                      log.debug("RDBMUserIdentityStore::addNewUser(USER_ID=" + templateUser.getUserId() + "): " + query);
                  rs = queryStmt.executeQuery();

                  insert =
                      "INSERT INTO UP_USER_PROFILE (USER_ID, PROFILE_ID, PROFILE_FNAME, PROFILE_NAME, DESCRIPTION, LAYOUT_ID, STRUCTURE_SS_ID, THEME_SS_ID) " +
                      "VALUES(?, ?, ?, ?, ?, NULL, ?, ?)";
                  insertStmt = con.prepareStatement(insert);
                  while (rs.next()) {
                	  int id = getNextKey();

                      String profileFname = rs.getString("PROFILE_FNAME");
                      String profileName = rs.getString("PROFILE_NAME");
                      String description = rs.getString("DESCRIPTION");
                      int structure = rs.getInt("STRUCTURE_SS_ID");
                      int theme = rs.getInt("THEME_SS_ID");

                      insertStmt.setInt(1, newUID);
                      insertStmt.setInt(2, id);
                      insertStmt.setString(3, profileFname);
                      insertStmt.setString(4, profileName);
                      insertStmt.setString(5, description);
                      insertStmt.setInt(6, structure);
                      insertStmt.setInt(7, theme);

                      if (log.isDebugEnabled())
                          log.debug("RDBMUserIdentityStore::addNewUser(USER_ID=" + newUID + ", PROFILE_FNAME=" + profileFname + ", PROFILE_NAME=" + profileName + ", DESCRIPTION=" + description + "): " + insert);
                      insertStmt.executeUpdate();
                  }
                  rs.close();
                  queryStmt.close();

                  if (insertStmt != null) {
                    insertStmt.close();
                    insertStmt = null;
                  }


                  // If we made it all the way though, commit the transaction
                  if (RDBMServices.getDbMetaData().supportsTransactions())
                      con.commit();

                  uPortalUID = newUID;

              } finally {
                  try { if (rs != null) rs.close(); } catch (Exception e) {}
              }
          } finally {
              try { if (queryStmt != null) queryStmt.close(); } catch (Exception e) {}
              try { if (insertStmt != null) insertStmt.close(); } catch (Exception e) {}
          }

          return uPortalUID;

                  }
              });
          }
      });
  }

  private int getNextKey()
  {
      return SequenceGenerator.instance().getNextInt(PROFILE_TABLE);
  }

  protected class PortalUser {
      String userName;
      int userId;
      int defaultUserId;
      public String getUserName() { return userName; }
      public int getUserId() { return userId; }
      public int getDefaultUserId() { return defaultUserId; }
      public void setUserName(String userName) { this.userName = userName; }
      public void setUserId(int userId) { this.userId = userId; }
      public void setDefaultUserId(int defaultUserId) { this.defaultUserId = defaultUserId; }
  }

  protected class TemplateUser {
      String userName;
      int userId;
      int defaultLayoutId;
      public String getUserName() { return userName; }
      public int getUserId() { return userId; }
      public int getDefaultLayoutId() { return defaultLayoutId; }
      public void setUserName(String userName) { this.userName = userName; }
      public void setUserId(int userId) { this.userId = userId; }
      public void setDefaultLayoutId(int defaultLayoutId) { this.defaultLayoutId = defaultLayoutId; }
  }

}



