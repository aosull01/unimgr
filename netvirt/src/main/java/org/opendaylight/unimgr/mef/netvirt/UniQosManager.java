/*
 * Copyright (c) 2016 Hewlett Packard Enterprise, Co. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.unimgr.mef.netvirt;

import com.google.common.base.Optional;

import jline.internal.Log;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.genius.mdsalutil.MDSALUtil;
import org.opendaylight.ovsdb.utils.southbound.utils.SouthboundUtils;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.global.rev150526.MefGlobal;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.global.rev150526.mef.global.Profiles;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.global.rev150526.mef.global.bwp.flows.group.BwpFlow;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.global.rev150526.mef.global.bwp.flows.group.BwpFlowKey;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.global.rev150526.mef.global.profiles.IngressBwpFlows;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.interfaces.rev150526.mef.interfaces.unis.uni.physical.layers.links.Link;
import org.opendaylight.yang.gen.v1.http.metroethernetforum.org.ns.yang.mef.types.rev150526.Identifier45;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.meta.rev160406.BridgeRefInfo;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.meta.rev160406.bridge.ref.info.BridgeRefEntry;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.meta.rev160406.bridge.ref.info.BridgeRefEntryKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rpcs.rev160406.GetDpidFromInterfaceInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rpcs.rev160406.GetDpidFromInterfaceInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rpcs.rev160406.GetDpidFromInterfaceOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rpcs.rev160406.OdlInterfaceRpcService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbBridgeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbTerminationPointAugmentation;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbTerminationPointAugmentationBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPointBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPointKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UniQosManager {
    private static final Logger LOG = LoggerFactory.getLogger(UniQosManager.class);
    private OdlInterfaceRpcService odlInterfaceRpcService;
    private DataBroker dataBroker;
    private final Long noLimit = 0l;

    // key in first map is uniId, key in second map is logical portId
    private ConcurrentHashMap<String, ConcurrentHashMap<String, BandwidthLimits>> uniPortBandwidthLimits;

    // map of current values per uni
    private ConcurrentHashMap<String, BandwidthLimits> uniBandwidthLimits;
    
    private ConcurrentHashMap<String, BigInteger> uniToDpn;

    public UniQosManager(final DataBroker dataBroker, OdlInterfaceRpcService odlInterfaceRpcService) {
        this.dataBroker = dataBroker;
        this.odlInterfaceRpcService = odlInterfaceRpcService;
        this.uniPortBandwidthLimits = new ConcurrentHashMap<>();
        this.uniBandwidthLimits = new ConcurrentHashMap<>();
        this.uniToDpn = new ConcurrentHashMap<>();
    }

    public void mapUniPortBandwidthLimits(String uniId, String portId, Long maxKbps, Long maxBurstKb) {
        Log.info("Record rate limits for Uni {} port {} maxKbps {} maxBurstKb {}", uniId, portId, maxKbps, maxBurstKb);
        uniPortBandwidthLimits.putIfAbsent(uniId, new ConcurrentHashMap<>());
        ConcurrentHashMap<String, BandwidthLimits> uniMap = uniPortBandwidthLimits.get(uniId);
        uniMap.put(portId, new BandwidthLimits(maxKbps, maxBurstKb));
    }

    public void mapUniPortBandwidthLimits(String uniId, String portId, Identifier45 bwProfile) {
        Long maxKbps = noLimit;
        Long maxBurstKb = noLimit;
        if (bwProfile != null) {

            Optional<BwpFlow> bwFlowOp = MdsalUtils.read(dataBroker, LogicalDatastoreType.CONFIGURATION,
                    getBwFlowInstanceIdentifier(bwProfile));
            if (!bwFlowOp.isPresent()) {
                Log.trace("Can't read bw profile {} for Uni {}", bwProfile, uniId);
                return;
            }
            // Kbytes per second
            maxKbps = bwFlowOp.get().getCir().getValue();
            // burst in bytes, ovs requires in Kb
            maxBurstKb = bwFlowOp.get().getCbs().getValue() / 1000;
            Log.info("Record rate limits for Uni {} Profile {}", uniId, bwProfile);
        }
        mapUniPortBandwidthLimits(uniId, portId, maxKbps, maxBurstKb);
    }

    public void unMapUniPortBandwidthLimits(String uniId, String portId) {
        Log.debug("Delete rate limits for Uni {} port {}", uniId, portId);
        ConcurrentHashMap<String, BandwidthLimits> uniMap = uniPortBandwidthLimits.get(uniId);
        if (uniMap == null) {
            Log.error("Trying to delete limits for non-exsting uni {}", uniId);
            return;
        }
        uniMap.remove(portId);
        if (uniMap.isEmpty()) {
            uniMap.put(portId, new BandwidthLimits(noLimit, noLimit));
        }
    }

    public void setUniBandwidthLimits(Identifier45 uniIden) {
        String uniId = uniIden.getValue();
        if (!uniPortBandwidthLimits.containsKey(uniId)) {
            Log.debug("Uni {} doesn't have rate limits configured", uniId);
            return;
        }
        Iterator<String> uniPorts = uniPortBandwidthLimits.get(uniId).keySet().iterator();
        if (uniPorts == null || !uniPorts.hasNext()) {
            Log.debug("Uni {} doesn't have rate limits configured", uniId);
            return;
        }
        String logicalPort = uniPorts.next();

        BandwidthLimits newLimits = recalculateLimitsForUni(uniId, uniPortBandwidthLimits.get(uniId));
        if (newLimits.equals(uniBandwidthLimits.get(uniId))) {
            Log.debug("Uni {} rate limits has not changed", uniId);
            return;
        }

        setPortBandwidthLimits(uniId, logicalPort, newLimits.getMaxKbps(), newLimits.getMaxBurstKb());
        uniBandwidthLimits.put(uniId, newLimits);
    }

    private BandwidthLimits recalculateLimitsForUni(String uniId,
            ConcurrentHashMap<String, BandwidthLimits> uniLimits) {
        Long sumOfRate = noLimit;
        Long sumOfBurst = noLimit;
        Boolean hasNullRate = false;
        Boolean hasNullBurst = false;

        if (uniLimits == null || uniLimits.keySet() == null) {
            return new BandwidthLimits(sumOfRate, sumOfBurst);
        }

        for (BandwidthLimits v : uniLimits.values()) {
            if (v.maxKbps == null) {
                hasNullRate = true;
                break;
            }
            if (v.maxBurstKb == null) {
                hasNullBurst = true;
            }
            sumOfRate = sumOfRate + v.maxKbps;
            sumOfBurst = sumOfBurst + v.maxBurstKb;

        }
        if (hasNullRate) {
            sumOfRate = noLimit;
            sumOfBurst = noLimit;
        } else if (hasNullBurst) {
            sumOfBurst = noLimit;
        }
        return new BandwidthLimits(sumOfRate, sumOfBurst);
    }

    private void setPortBandwidthLimits(String uniId, String logicalPortId, Long maxKbps, Long maxBurstKb) {
        LOG.trace("Setting bandwidth limits {} {} on Port {}", maxKbps, maxBurstKb, logicalPortId);

        BigInteger dpId = BigInteger.ZERO;
        if(uniToDpn.containsKey(uniId)) {
            dpId = uniToDpn.get(uniId);
        } else {        
            dpId = getDpnForInterface(odlInterfaceRpcService, logicalPortId);
            uniToDpn.put(uniId, dpId);
        }
        if (dpId.equals(BigInteger.ZERO)) {
            LOG.error("DPN ID for interface {} not found", logicalPortId);
            return;
        }

        OvsdbBridgeRef bridgeRefEntry = getBridgeRefEntryFromOperDS(dpId, dataBroker);
        Optional<Node> bridgeNode = MDSALUtil.read(LogicalDatastoreType.OPERATIONAL,
                bridgeRefEntry.getValue().firstIdentifierOf(Node.class), dataBroker);
        if (bridgeNode == null) {
            LOG.error("Bridge ref for interface {} not found", logicalPortId);
            return;
        }

        String physicalPort = getPhysicalPortForUni(dataBroker, uniId);
        if (physicalPort == null) {
            LOG.error("Physical port for interface {} not found", logicalPortId);
            return;
        }

        TerminationPoint tp = getTerminationPoint(bridgeNode.get(), physicalPort);
        if (tp == null) {
            LOG.error("Termination point for port {} not found", physicalPort);
            return;
        }

        OvsdbTerminationPointAugmentation ovsdbTp = tp.getAugmentation(OvsdbTerminationPointAugmentation.class);
        OvsdbTerminationPointAugmentationBuilder tpAugmentationBuilder = new OvsdbTerminationPointAugmentationBuilder();
        tpAugmentationBuilder.setName(ovsdbTp.getName());
        tpAugmentationBuilder.setIngressPolicingRate(maxKbps);
        tpAugmentationBuilder.setIngressPolicingBurst(maxBurstKb);

        TerminationPointBuilder tpBuilder = new TerminationPointBuilder();
        tpBuilder.setKey(tp.getKey());
        tpBuilder.addAugmentation(OvsdbTerminationPointAugmentation.class, tpAugmentationBuilder.build());
        MdsalUtils.syncUpdate(dataBroker, LogicalDatastoreType.CONFIGURATION,
                InstanceIdentifier.create(NetworkTopology.class)
                        .child(Topology.class, new TopologyKey(SouthboundUtils.OVSDB_TOPOLOGY_ID))
                        .child(Node.class, bridgeNode.get().getKey())
                        .child(TerminationPoint.class, new TerminationPointKey(tp.getKey())),
                tpBuilder.build());
    }

    private static TerminationPoint getTerminationPoint(Node bridgeNode, String portName) {
        for (TerminationPoint tp : bridgeNode.getTerminationPoint()) {
            String tpIdStr = tp.getTpId().getValue();
            if (tpIdStr != null && tpIdStr.equals(portName))
                return tp;
        }
        return null;
    }

    private static BigInteger getDpnForInterface(OdlInterfaceRpcService interfaceManagerRpcService, String ifName) {
        BigInteger nodeId = BigInteger.ZERO;
        try {
            GetDpidFromInterfaceInput dpIdInput = new GetDpidFromInterfaceInputBuilder().setIntfName(ifName).build();
            Future<RpcResult<GetDpidFromInterfaceOutput>> dpIdOutput = interfaceManagerRpcService
                    .getDpidFromInterface(dpIdInput);
            RpcResult<GetDpidFromInterfaceOutput> dpIdResult = dpIdOutput.get();
            if (dpIdResult.isSuccessful()) {
                nodeId = dpIdResult.getResult().getDpid();
            } else {
                LOG.error("Could not retrieve DPN Id for interface {}", ifName);
            }
        } catch (NullPointerException | InterruptedException | ExecutionException e) {
            LOG.error("Exception when getting dpn for interface {}", ifName, e);
        }
        return nodeId;
    }

    private static String getPhysicalPortForUni(DataBroker dataBroker, String uniId) {
        String nodeId = null;
        try {
            Link link = MefInterfaceUtils.getLink(dataBroker, uniId, LogicalDatastoreType.OPERATIONAL);
            String parentInterfaceName = MefInterfaceUtils.getTrunkParentName(link);
            return parentInterfaceName.split(":")[1];
        } catch (Exception e) {
            LOG.error("Exception when getting physical port for Uni {}", uniId, e);
        }
        return nodeId;
    }

    private static BridgeRefEntry getBridgeRefEntryFromOperDS(InstanceIdentifier<BridgeRefEntry> dpnBridgeEntryIid,
            DataBroker dataBroker) {
        Optional<BridgeRefEntry> bridgeRefEntryOptional = MdsalUtils.read(dataBroker, LogicalDatastoreType.OPERATIONAL,
                dpnBridgeEntryIid);
        if (!bridgeRefEntryOptional.isPresent()) {
            return null;
        }
        return bridgeRefEntryOptional.get();
    }

    private static OvsdbBridgeRef getBridgeRefEntryFromOperDS(BigInteger dpId, DataBroker dataBroker) {
        BridgeRefEntryKey bridgeRefEntryKey = new BridgeRefEntryKey(dpId);
        InstanceIdentifier<BridgeRefEntry> bridgeRefEntryIid = getBridgeRefEntryIdentifier(bridgeRefEntryKey);
        BridgeRefEntry bridgeRefEntry = getBridgeRefEntryFromOperDS(bridgeRefEntryIid, dataBroker);
        return (bridgeRefEntry != null) ? bridgeRefEntry.getBridgeReference() : null;
    }

    private static InstanceIdentifier<BridgeRefEntry> getBridgeRefEntryIdentifier(BridgeRefEntryKey bridgeRefEntryKey) {
        InstanceIdentifier.InstanceIdentifierBuilder<BridgeRefEntry> bridgeRefEntryInstanceIdentifierBuilder = InstanceIdentifier
                .builder(BridgeRefInfo.class).child(BridgeRefEntry.class, bridgeRefEntryKey);
        return bridgeRefEntryInstanceIdentifierBuilder.build();
    }

    private static InstanceIdentifier<BwpFlow> getBwFlowInstanceIdentifier(Identifier45 bwProfile) {
        InstanceIdentifier.InstanceIdentifierBuilder<BwpFlow> bwProfileInstanceIdentifierBuilder = InstanceIdentifier
                .builder(MefGlobal.class).child(Profiles.class).child(IngressBwpFlows.class)
                .child(BwpFlow.class, new BwpFlowKey(bwProfile));
        return bwProfileInstanceIdentifierBuilder.build();
    }

    private class BandwidthLimits {
        private final Long maxKbps;
        private final Long maxBurstKb;

        public BandwidthLimits(Long maxKbps, Long maxBurstKb) {
            this.maxKbps = replaceNull(maxKbps);
            this.maxBurstKb = replaceNull(maxBurstKb);
        }

        public Long getMaxKbps() {
            return maxKbps;
        }

        public Long getMaxBurstKb() {
            return maxBurstKb;
        }

        private Long replaceNull(Long value) {
            return (value == null) ? Long.valueOf(0) : value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            BandwidthLimits other = (BandwidthLimits) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (maxBurstKb == null) {
                if (other.maxBurstKb != null)
                    return false;
            } else if (!maxBurstKb.equals(other.maxBurstKb))
                return false;
            if (maxKbps == null) {
                if (other.maxKbps != null)
                    return false;
            } else if (!maxKbps.equals(other.maxKbps))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "BandwidthLimitsBandwidthLimitsalues [maxKbps=" + maxKbps + ", maxBurstKb=" + maxBurstKb + "]";
        }

        private UniQosManager getOuterType() {
            return UniQosManager.this;
        }
    }
}