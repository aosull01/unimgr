/*
 * Copyright (c) 2018 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.unimgr.mef.nrp.impl.topologyservice;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.unimgr.mef.nrp.api.TapiConstants;
import org.opendaylight.unimgr.mef.nrp.impl.AbstractTestWithTopo;
import org.opendaylight.unimgr.mef.nrp.impl.topologytervice.TapiTopologyServiceImpl;
import org.opendaylight.yang.gen.v1.urn.onf.params.xml.ns.yang.tapi.common.rev171113.OperationalState;
import org.opendaylight.yang.gen.v1.urn.onf.params.xml.ns.yang.tapi.topology.rev171113.*;
import org.opendaylight.yang.gen.v1.urn.onf.params.xml.ns.yang.tapi.topology.rev171113.get.link.details.output.Link;
import org.opendaylight.yang.gen.v1.urn.onf.params.xml.ns.yang.tapi.topology.rev171113.get.topology.list.output.Topology;
import org.opendaylight.yangtools.yang.common.RpcResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author bartosz.michalik@amartus.com
 */
public class TapiTopologyServiceImplIntTest extends AbstractTestWithTopo {

    private String uuid1 = "uuid1";
    private String uuid2 = "uuid2";

    private TapiTopologyServiceImpl tapiTopologyService;

    @Before
    public void setUp() throws Exception {
        tapiTopologyService = new TapiTopologyServiceImpl();
        tapiTopologyService.setBroker(dataBroker);
        tapiTopologyService.init();
    }

    @Test
    public void getTopologies() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetTopologyListOutput> output = tapiTopologyService.getTopologyList().get();

        Assert.assertTrue(output.isSuccessful());

        List<Topology> topologies = output.getResult().getTopology();

        Assert.assertEquals(2, topologies.size());

    }

    @Test
    public void getTopologyUnknown() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetTopologyDetailsOutput> output = tapiTopologyService
                .getTopologyDetails(new GetTopologyDetailsInputBuilder().setTopologyIdOrName("unknown").build()).get();

        Assert.assertFalse(output.isSuccessful());

    }


    @Test
    public void getTopologySystem() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetTopologyDetailsOutput> output = tapiTopologyService
                .getTopologyDetails(new GetTopologyDetailsInputBuilder().setTopologyIdOrName(TapiConstants.PRESTO_SYSTEM_TOPO).build()).get();

        Assert.assertTrue(output.isSuccessful());

        Assert.assertEquals(TapiConstants.PRESTO_SYSTEM_TOPO, output.getResult().getTopology().getUuid().getValue());

    }


    @Test
    public void getTopologyNode() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetNodeDetailsOutput> output = tapiTopologyService
                .getNodeDetails(new GetNodeDetailsInputBuilder()
                        .setNodeIdOrName(uuid1)
                        .setTopologyIdOrName(TapiConstants.PRESTO_SYSTEM_TOPO).build())
                .get();

        Assert.assertTrue(output.isSuccessful());

        Assert.assertEquals(uuid1, output.getResult().getNode().getUuid().getValue());

    }

    @Test
    public void getTopologyNodeWrongTopology() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetNodeDetailsOutput> output = tapiTopologyService
                .getNodeDetails(new GetNodeDetailsInputBuilder()
                        .setNodeIdOrName(uuid1)
                        .setTopologyIdOrName("some_topo").build())
                .get();

        Assert.assertFalse(output.isSuccessful());
    }


    @Test
    public void getLinkNotFound() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        tx.submit().checkedGet();

        RpcResult<GetLinkDetailsOutput> output = tapiTopologyService
                .getLinkDetails(new GetLinkDetailsInputBuilder()
                        .setLinkIdOrName("any_link")
                        .setTopologyIdOrName("some_topo").build())
                .get();

        Assert.assertFalse(output.isSuccessful());
    }

    @Test
    public void getLink() throws TransactionCommitFailedException, ExecutionException, InterruptedException {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        String linkId = uuid1 + "-" + uuid2;
        n(tx, uuid1, uuid1 + ":1", uuid1 + ":2", uuid1 + ":3");
        n(tx, uuid2, uuid2 + ":1", uuid2 + ":2", uuid2 + ":3");
        org.opendaylight.yang.gen.v1.urn.onf.params.xml.ns.yang.tapi.topology.rev171113.topology.Link l = l(tx, uuid1, uuid1 + ":1", uuid2, uuid2 + ":1", OperationalState.ENABLED);
        tx.submit().checkedGet();

        RpcResult<GetLinkDetailsOutput> output = tapiTopologyService
                .getLinkDetails(new GetLinkDetailsInputBuilder()
                        .setLinkIdOrName(l.getUuid().getValue())
                        .setTopologyIdOrName(TapiConstants.PRESTO_SYSTEM_TOPO).build())
                .get();

        Assert.assertTrue(output.isSuccessful());
        Link link = output.getResult().getLink();
        Set<String> nodeUuids = link.getNode().stream().map(u -> u.getValue()).collect(Collectors.toSet());
        Assert.assertTrue(nodeUuids.contains(uuid1));
        Assert.assertTrue(nodeUuids.contains(uuid2));
    }

}
