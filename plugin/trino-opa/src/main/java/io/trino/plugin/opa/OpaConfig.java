/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.opa;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.Optional;

public class OpaConfig
{
    private URI opaUri;

    private Optional<URI> opaBatchUri = Optional.empty();
    private boolean logRequests;
    private boolean logResponses;
    private boolean allowPermissionManagementOperations;

    @NotNull
    public URI getOpaUri()
    {
        return opaUri;
    }

    @Config("opa.policy.uri")
    @ConfigDescription("URI for OPA policies")
    public OpaConfig setOpaUri(@NotNull URI opaUri)
    {
        this.opaUri = opaUri;
        return this;
    }

    public Optional<URI> getOpaBatchUri()
    {
        return opaBatchUri;
    }

    @Config("opa.policy.batched-uri")
    @ConfigDescription("URI for Batch OPA policies - if not set, a single request will be sent for each entry on filtering methods")
    public OpaConfig setOpaBatchUri(URI opaBatchUri)
    {
        this.opaBatchUri = Optional.ofNullable(opaBatchUri);
        return this;
    }

    public boolean getLogRequests()
    {
        return this.logRequests;
    }

    @Config("opa.log-requests")
    @ConfigDescription("Whether to log requests (URI, entire body and headers) prior to sending them to OPA")
    public OpaConfig setLogRequests(boolean logRequests)
    {
        this.logRequests = logRequests;
        return this;
    }

    public boolean getLogResponses()
    {
        return this.logResponses;
    }

    @Config("opa.log-responses")
    @ConfigDescription("Whether to log responses (URI, entire body, status code and headers) received from OPA")
    public OpaConfig setLogResponses(boolean logResponses)
    {
        this.logResponses = logResponses;
        return this;
    }

    public boolean getAllowPermissionManagementOperations()
    {
        return this.allowPermissionManagementOperations;
    }

    @Config("opa.allow-permission-management-operations")
    @ConfigDescription("Whether to allow permission management (GRANT, DENY, ...) and role management operations - OPA will not be queried for any such operations, they will be bulk allowed or denied depending on this setting")
    public OpaConfig setAllowPermissionManagementOperations(boolean allowPermissionManagementOperations)
    {
        this.allowPermissionManagementOperations = allowPermissionManagementOperations;
        return this;
    }
}
