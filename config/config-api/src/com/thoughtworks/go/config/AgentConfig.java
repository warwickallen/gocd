/*
 * Copyright 2016 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.thoughtworks.go.domain.ConfigErrors;
import com.thoughtworks.go.domain.IpAddress;
import com.thoughtworks.go.remote.AgentIdentifier;
import com.thoughtworks.go.util.StringUtil;
import com.thoughtworks.go.util.SystemUtil;

import static java.lang.String.format;

/**
 * @understands the current persistent information related to an Agent
 */
@ConfigTag("agent")
public class AgentConfig implements Validatable {
    @ConfigAttribute(value = "hostname", allowNull = true)
    private String hostName;
    @ConfigAttribute(value = "ipaddress", allowNull = true)
    private String ipAddress;
    @ConfigAttribute(value = "uuid", allowNull = true)
    private String uuid;
    @ConfigAttribute(value = "isDisabled")
    private Boolean isDisabled = false;
    @ConfigSubtag
    private Resources resources = new Resources();

    private transient Boolean cachedIsFromLocalHost;
    private ConfigErrors errors = new ConfigErrors();
    public static final String IP_ADDRESS = "ipAddress";
    public static final String UUID = "uuid";

    public AgentConfig() {
    }

    public AgentConfig(String uuid) {
        this(uuid, "", "");
    }

    public AgentConfig(String uuid, String hostName, String ipAddress) {
        this(uuid, hostName, ipAddress, new Resources());
    }

    public AgentConfig(String uuid, String hostName, String ipAddress, Resources resources) {
        this.hostName = hostName;
        this.ipAddress = ipAddress;
        this.uuid = uuid;
        this.resources = resources;
    }

    public boolean validateTree(ValidationContext validationContext) {
        validate(validationContext);
        boolean isValid = errors().isEmpty();
        isValid = resources.validateTree(validationContext) && isValid;
        return isValid;
    }
    public void validate(ValidationContext validationContext) {
        validateIpAddress();
        if(StringUtil.isBlank(uuid)){
            addError(UUID, "UUID cannot be empty");
        }
    }

    private void validateIpAddress() {
        String address = getIpAddress();
        if (address == null) {
            return;
        }
        if (StringUtil.isBlank(address)) {
            addError(IP_ADDRESS, "IpAddress cannot be empty if it is present.");
            return;
        }
        try {
            IpAddress.create(address);
        } catch (Exception e) {
            addError(IP_ADDRESS, String.format("'%s' is an invalid IP address.", address));
        }
    }

    public void addError(String fieldName, String msg) {
        errors.add(fieldName, msg);
    }

    public ConfigErrors errors() {
        return errors;
    }

    public boolean hasAllResources(Collection<Resource> required) {
        return this.resources.containsAll(required);
    }

    public Resources getResources() {
        return resources;
    }

    public void addResource(Resource resource) {
        this.resources.add(resource);
    }

    public void removeResource(Resource resource) {
        resources.remove(resource);
    }

    public void setResources(Resources resources) {
        this.resources = resources;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public boolean isDisabled() {
        return Boolean.TRUE.equals(isDisabled);
    }

    public void setDisabled(Boolean disabled) {
        isDisabled = disabled;
    }

    public void disable() {
        disable(Boolean.TRUE);
    }

    public void disable(Boolean aBoolean) {
        setDisabled(aBoolean);
    }

    public boolean isNull() {
        return false;
    }

    public boolean isFromLocalHost() {
        if (cachedIsFromLocalHost == null) {
            cachedIsFromLocalHost = SystemUtil.isLocalhost(ipAddress);
        }
        return cachedIsFromLocalHost.booleanValue();
    }

    public boolean isFromRemoteHost() {
        return !isFromLocalHost();
    }

    public String getIpAddress() {
        return this.ipAddress;
    }


    public String getHostname() {
        return this.hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getHostNameForDispaly() {
        return this.hostName;
    }

    public String getUuid() {
        return uuid;
    }

    @Deprecated
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String toString() {
        return format("Agent [%s, %s, %s]", hostName, ipAddress, uuid);
    }

    public AgentIdentifier getAgentIdentifier() {
        return new AgentIdentifier(this.getHostname(), getIpAddress(), getUuid());
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AgentConfig agentConfig = (AgentConfig) o;

        if (hostName != null ? !hostName.equals(agentConfig.hostName) : agentConfig.hostName != null) {
            return false;
        }
        if (ipAddress != null ? !ipAddress.equals(agentConfig.ipAddress) : agentConfig.ipAddress != null) {
            return false;
        }
        if (isDisabled != null ? !isDisabled.equals(agentConfig.isDisabled) : agentConfig.isDisabled != null) {
            return false;
        }
        if (resources != null ? !resources.equals(agentConfig.resources) : agentConfig.resources != null) {
            return false;
        }
        if (uuid != null ? !uuid.equals(agentConfig.uuid) : agentConfig.uuid != null) {
            return false;
        }

        return true;
    }

    public int hashCode() {
        int result;
        result = (hostName != null ? hostName.hashCode() : 0);
        result = 31 * result + (ipAddress != null ? ipAddress.hashCode() : 0);
        result = 31 * result + (uuid != null ? uuid.hashCode() : 0);
        result = 31 * result + (isDisabled != null ? isDisabled.hashCode() : 0);
        result = 31 * result + (resources != null ? resources.hashCode() : 0);
        return result;
    }
}
