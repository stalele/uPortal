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

package org.jasig.portal.character.stream.events;

import org.jasig.portal.portlet.om.IPortletWindowId;



/**
 * @author Eric Dalquist
 * @version $Revision$
 */
public class PortletContentPlaceholderEventImpl extends PortletPlaceholderEventImpl implements PortletContentPlaceholderEvent {
    private static final long serialVersionUID = 1L;

    public PortletContentPlaceholderEventImpl(IPortletWindowId portletWindowId) {
        super(portletWindowId);
    }
    
    /* (non-Javadoc)
     * @see org.jasig.portal.character.stream.events.CharacterEvent#getEventType()
     */
    @Override
    public CharacterEventTypes getEventType() {
        return CharacterEventTypes.PORTLET_CONTENT;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.getPortletWindowId() == null) ? 0 : this.getPortletWindowId().hashCode());
        result = prime * result + ((this.getEventType() == null) ? 0 : this.getEventType().hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (PortletContentPlaceholderEvent.class.isAssignableFrom(obj.getClass()))
            return false;
        PortletContentPlaceholderEvent other = (PortletContentPlaceholderEvent) obj;
        if (this.getEventType() == null) {
            if (other.getEventType() != null)
                return false;
        }
        else if (!this.getEventType().equals(other.getEventType()))
            return false;
        if (this.getPortletWindowId() == null) {
            if (other.getPortletWindowId() != null)
                return false;
        }
        else if (!this.getPortletWindowId().equals(other.getPortletWindowId()))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "PortletContentPlaceholderEvent [" +
        		"eventType=" + this.getEventType() + ", " +
				"portletWindowId=" + this.getPortletWindowId() + "]";
    }
}
