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

package org.jasig.portal.user;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.portal.GuestUserInstance;
import org.jasig.portal.GuestUserPreferencesManager;
import org.jasig.portal.PortalException;
import org.jasig.portal.UserInstance;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.security.IPersonManager;
import org.jasig.portal.security.ISecurityContext;
import org.jasig.portal.security.PortalSecurityException;
import org.jasig.portal.spring.web.context.support.HttpSessionDestroyedEvent;
import org.jasig.portal.url.IPortalRequestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

/**
 * Determines which user instance object to use for a given user.
 *
 * @author Peter Kharchenko  {@link <a href="mailto:pkharchenko@interactivebusiness.com"">pkharchenko@interactivebusiness.com"</a>}
 * @version $Revision 1.1$
 */
@Service("userInstanceManager")
public class UserInstanceManagerImpl implements IUserInstanceManager, ApplicationListener {
    private static final String KEY = UserInstanceManagerImpl.class.getName() + ".USER_INSTANCE";
    
    protected final Log logger = LogFactory.getLog(UserInstanceManagerImpl.class);
    
    private Map<Integer, GuestUserPreferencesManager> guestUserPreferencesManagers = new HashMap<Integer, GuestUserPreferencesManager>();
    
    private IPersonManager personManager;
    private IPortalRequestUtils portalRequestUtils;
    
    /**
     * @return the personManager
     */
    public IPersonManager getPersonManager() {
        return personManager;
    }
    /**
     * @param personManager the personManager to set
     */
    @Autowired(required=true)
    public void setPersonManager(IPersonManager personManager) {
        this.personManager = personManager;
    }
    
    public IPortalRequestUtils getPortalRequestUtils() {
        return portalRequestUtils;
    }
    /**
     * @param portalRequestUtils 
     */
    @Autowired(required=true)
    public void setPortalRequestUtils(IPortalRequestUtils portalRequestUtils) {
        this.portalRequestUtils = portalRequestUtils;
    }
    
    
    /**
     * Returns the UserInstance object that is associated with the given request.
     * @param request Incoming HttpServletRequest
     * @return UserInstance object associated with the given request
     */
    public IUserInstance getUserInstance(HttpServletRequest request) throws PortalException {
        try {
            request = this.portalRequestUtils.getOriginalPortalRequest(request);
        }
        catch (IllegalArgumentException iae) {
            //ignore, just means that this isn't a wrapped request
        }
        
        //Use request attributes first for the fastest possible retrieval
        IUserInstance userInstance = (IUserInstance)request.getAttribute(KEY);
        if (userInstance != null) {
            return userInstance;
        }
        
        final IPerson person;
        try {
            // Retrieve the person object that is associated with the request
            person = this.personManager.getPerson(request);
        }
        catch (Exception e) {
            logger.error("Exception while retrieving IPerson!", e);
            throw new PortalSecurityException("Could not retrieve IPerson", e);
        }
        
        if (person == null) {
            throw new PortalSecurityException("PersonManager returned null person for this request.  With no user, there's no UserInstance.  Is PersonManager misconfigured?  RDBMS access misconfigured?");
        }

        final HttpSession session = request.getSession();
        if (session == null) {
            throw new IllegalStateException("HttpServletRequest.getSession() returned a null session for request: " + request);
        }

        // Return the UserInstance object if it's in the session
        UserInstanceHolder userInstanceHolder = getUserInstanceHolder(session);
        if (userInstanceHolder != null) {
            userInstance = userInstanceHolder.getUserInstance();
            
            if (userInstance != null) {
                return userInstance;
            }
        }

        // Create either a UserInstance or a GuestUserInstance
        if (person.isGuest()) {
            final Integer personId = person.getID();
            
            //Get or Create a shared GuestUserPreferencesManager for the Guest IPerson
            //sync so multiple managers aren't created for a single guest
            GuestUserPreferencesManager guestUserPreferencesManager;
            synchronized (guestUserPreferencesManagers) {
                guestUserPreferencesManager = guestUserPreferencesManagers.get(personId);
                if (guestUserPreferencesManager == null) {
                    guestUserPreferencesManager = new GuestUserPreferencesManager(person);
                    guestUserPreferencesManagers.put(personId, guestUserPreferencesManager);
                }
            }
            
            userInstance = new GuestUserInstance(person, guestUserPreferencesManager, request);
        }
        else {
            final ISecurityContext securityContext = person.getSecurityContext();
            if (securityContext.isAuthenticated()) {
                userInstance = new UserInstance(person, request);
            }
            else {
                // we can't allow for unauthenticated, non-guest user to come into the system
                throw new PortalSecurityException("System does not allow for unauthenticated non-guest users.");
            }
        }

        //Ensure the newly created UserInstance is cached in the session
        if (userInstanceHolder == null) {
            userInstanceHolder = new UserInstanceHolder();
        }
        userInstanceHolder.setUserInstance(userInstance);
        session.setAttribute(KEY, userInstanceHolder);
        request.setAttribute(KEY, userInstance);

        // Return the new UserInstance
        return userInstance;
    }
    
    /* (non-Javadoc)
     * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
     */
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof HttpSessionDestroyedEvent) {
            final HttpSession session = ((HttpSessionDestroyedEvent)event).getSession();

            final UserInstanceHolder userInstanceHolder = this.getUserInstanceHolder(session);
            if (userInstanceHolder == null) {
                return;
            }
            
            final IUserInstance userInstance = userInstanceHolder.getUserInstance();
            if (userInstance != null) {
                userInstance.destroySession(session);
            }
        }
    }

    protected UserInstanceHolder getUserInstanceHolder(final HttpSession session) {
        return (UserInstanceHolder) session.getAttribute(KEY);
    }

    /**
     * <p>Serializable wrapper class so the UserInstance object can
     * be indirectly stored in the session. The manager can deal with
     * this class returning a null value and its field is transient
     * so the session can be serialized successfully with the
     * UserInstance object in it.</p>
     * <p>Implements HttpSessionBindingListener and delegates those methods to
     * the wrapped UserInstance, if present.</p>
     */
    private static class UserInstanceHolder implements Serializable {
        private static final long serialVersionUID = 1L;

        private transient IUserInstance ui = null;

        /**
         * @return Returns the userInstance.
         */
        protected IUserInstance getUserInstance() {
            return this.ui;
        }

        /**
         * @param userInstance The userInstance to set.
         */
        protected void setUserInstance(IUserInstance userInstance) {
            this.ui = userInstance;
        }
    }
}
