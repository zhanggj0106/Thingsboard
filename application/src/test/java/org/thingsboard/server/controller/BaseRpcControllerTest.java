/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MvcResult;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.rpc.Rpc;
import org.thingsboard.server.common.data.rpc.RpcStatus;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.rpc.RpcDao;

import java.sql.SQLException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ContextConfiguration(classes = {BaseRpcControllerTest.Config.class})
public abstract class BaseRpcControllerTest extends AbstractControllerTest {

    private Tenant savedTenant;
    private User tenantAdmin;


    @Autowired
    private RpcDao rpcDao;

    static class Config {
        @Bean
        @Primary
        public RpcDao rpcDao(RpcDao rpcDao) {
            return Mockito.mock(RpcDao.class, AdditionalAnswers.delegatesTo(rpcDao));
        }
    }

    @Before
    public void beforeTest() throws Exception {
        loginSysAdmin();

        Tenant tenant = new Tenant();
        tenant.setTitle("My tenant");
        savedTenant = doPost("/api/tenant", tenant, Tenant.class);
        Assert.assertNotNull(savedTenant);

        tenantAdmin = new User();
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setTenantId(savedTenant.getId());
        tenantAdmin.setEmail("tenant2@thingsboard.org");
        tenantAdmin.setFirstName("Joe");
        tenantAdmin.setLastName("Downs");

        tenantAdmin = createUserAndLogin(tenantAdmin, "testPassword1");
    }

    @After
    public void afterTest() throws Exception {
        loginSysAdmin();

        doDelete("/api/tenant/" + savedTenant.getId().getId().toString())
                .andExpect(status().isOk());
    }

    private Device createDefaultDevice() {
        Device device = new Device();
        device.setName("My device");
        device.setType("default");

        return device;
    }

    private ObjectNode createDefaultRpc() {
        ObjectNode rpc = JacksonUtil.newObjectNode();
        rpc.put("method", "setGpio");

        ObjectNode params = JacksonUtil.newObjectNode();

        params.put("pin", 7);
        params.put("value", 1);

        rpc.set("params", params);
        rpc.put("persistent", true);
        rpc.put("timeout", 5000);

        return rpc;
    }

    private Rpc getRpcById(String rpcId) throws Exception {
        return doGet("/api/rpc/persistent/" + rpcId, Rpc.class);
    }

    private MvcResult removeRpcById(String rpcId) throws Exception {
        return doDelete("/api/rpc/persistent/" + rpcId).andReturn();
    }

    @Test
    public void testSaveRpc() throws Exception {
        Device device = createDefaultDevice();
        Device savedDevice = doPost("/api/device", device, Device.class);

        ObjectNode rpc = createDefaultRpc();
        String result = doPostAsync(
                "/api/rpc/oneway/" + savedDevice.getId().getId().toString(),
                JacksonUtil.toString(rpc),
                String.class,
                status().isOk()
        );
        String rpcId = JacksonUtil.fromString(result, JsonNode.class)
                .get("rpcId")
                .asText();
        Rpc savedRpc = getRpcById(rpcId);

        Assert.assertNotNull(savedRpc);
        Assert.assertEquals(savedDevice.getId(), savedRpc.getDeviceId());
    }

    @Test
    public void testDeleteRpc() throws Exception {
        Device device = createDefaultDevice();
        Device savedDevice = doPost("/api/device", device, Device.class);

        ObjectNode rpc = createDefaultRpc();
        String result = doPostAsync(
                "/api/rpc/oneway/" + savedDevice.getId().getId().toString(),
                JacksonUtil.toString(rpc),
                String.class,
                status().isOk()
        );
        String rpcId = JacksonUtil.fromString(result, JsonNode.class)
                .get("rpcId")
                .asText();
        Rpc savedRpc = getRpcById(rpcId);

        MvcResult mvcResult = removeRpcById(savedRpc.getId().getId().toString());
        MvcResult res = doGet("/api/rpc/persistent/" + rpcId)
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode deleteResponse = JacksonUtil.fromString(res.getResponse().getContentAsString(), JsonNode.class);
        Assert.assertEquals(404, deleteResponse.get("status").asInt());

        String url = "/api/rpc/persistent/device/" + savedDevice.getUuidId().toString()
                + "?" + "page=0" + "&" +
                "pageSize=" + Integer.MAX_VALUE + "&" +
                "rpcStatus=" + RpcStatus.DELETED.name();
        MvcResult byDeviceResult = doGet(url).andReturn();
        JsonNode byDeviceResponse = JacksonUtil.fromString(byDeviceResult.getResponse().getContentAsString(), JsonNode.class);

        Assert.assertEquals(500, byDeviceResponse.get("status").asInt());
    }

    @Test
    public void testGetRpcsByDeviceId() throws Exception {
        Device device = createDefaultDevice();
        Device savedDevice = doPost("/api/device", device, Device.class);

        ObjectNode rpc = createDefaultRpc();

        String result = doPostAsync(
                "/api/rpc/oneway/" + savedDevice.getId().getId().toString(),
                JacksonUtil.toString(rpc),
                String.class,
                status().isOk()
        );
        String rpcId = JacksonUtil.fromString(result, JsonNode.class)
                .get("rpcId")
                .asText();

        String url = "/api/rpc/persistent/device/" + savedDevice.getId().getId()
                + "?" + "page=0" + "&" +
                "pageSize=" + Integer.MAX_VALUE + "&" +
                "rpcStatus=" + RpcStatus.QUEUED;

        MvcResult byDeviceResult = doGetAsync(url).andReturn();

        List<Rpc> byDeviceRpcs = JacksonUtil.fromString(
                byDeviceResult
                        .getResponse()
                        .getContentAsString(),
                new TypeReference<PageData<Rpc>>() {}
        ).getData();


        boolean found = byDeviceRpcs.stream().anyMatch(r ->
                r.getUuidId().toString().equals(rpcId)
                        && r.getDeviceId().equals(savedDevice.getId())
        );

        Assert.assertTrue(found);
    }


    @Test
    public void testDeleteRpcWithTransactionalOk() throws Exception {
        Device device = createDefaultDevice();
        Device savedDevice = doPost("/api/device", device, Device.class);

        ObjectNode rpc = createDefaultRpc();
        String result = doPostAsync(
                "/api/rpc/oneway/" + savedDevice.getId().getId().toString(),
                JacksonUtil.toString(rpc),
                String.class,
                status().isOk()
        );
        String rpcId = JacksonUtil.fromString(result, JsonNode.class)
                .get("rpcId")
                .asText();
        Rpc savedRpc = getRpcById(rpcId);
        Assert.assertNotNull(savedRpc);

        MvcResult mvcResult = removeRpcById(savedRpc.getId().getId().toString());
        Assert.assertEquals(200, mvcResult.getResponse().getStatus());

        doGet("/api/rpc/persistent/" + rpcId)
                .andExpect(status().isNotFound());
    }

    @Test
    public void testDeleteRpcWithTransactionalException() throws Exception {
        Device device = createDefaultDevice();
        Device savedDevice = doPost("/api/device", device, Device.class);

        ObjectNode rpc = createDefaultRpc();
        String result = doPostAsync(
                "/api/rpc/oneway/" + savedDevice.getId().getId().toString(),
                JacksonUtil.toString(rpc),
                String.class,
                status().isOk()
        );
        String rpcId = JacksonUtil.fromString(result, JsonNode.class)
                .get("rpcId")
                .asText();
        Rpc savedRpc = getRpcById(rpcId);
        Assert.assertNotNull(savedRpc);

        Mockito.doThrow(new ConstraintViolationException("mock message", new SQLException(), "MOCK_CONSTRAINT")).when(rpcDao).removeById(any(), any());
        try {
            MvcResult mvcResult = removeRpcById(savedRpc.getId().getId().toString());
            Assert.assertEquals(500, mvcResult.getResponse().getStatus());

            doGet("/api/rpc/persistent/" + rpcId).andExpect(status().isOk());
            Mockito.doReturn(true).when(rpcDao).removeById(any(), any());
        } finally {
            Mockito.reset(rpcDao);
        }
    }

}
