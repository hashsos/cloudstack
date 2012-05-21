// Copyright 2012 Citrix Systems, Inc. Licensed under the
// Apache License, Version 2.0 (the "License"); you may not use this
// file except in compliance with the License.  Citrix Systems, Inc.
// reserves all rights not expressly granted by the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// 
// Automatically generated by addcopyright.py at 04/03/2012
package com.cloud.baremetal.networkservice;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand;
import com.cloud.agent.api.baremetal.PreparePxeServerAnswer;
import com.cloud.agent.api.baremetal.PreparePxeServerCommand;
import com.cloud.agent.api.baremetal.prepareCreateTemplateCommand;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand.BootDev;
import com.cloud.baremetal.database.BaremetalPxeVO;
import com.cloud.baremetal.networkservice.BaremetalPxeService;
import com.cloud.baremetal.networkservice.BaremetalPxeManager.BaremetalPxeType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PhysicalNetworkVO;
import com.cloud.network.ExternalNetworkDeviceManager.NetworkDevice;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ServerResource;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.Inject;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineProfile;

@Local(value=BaremetalPxeService.class)
public class BareMetalPingServiceImpl extends BareMetalPxeServiceBase implements BaremetalPxeService {
	private static final Logger s_logger = Logger.getLogger(BareMetalPingServiceImpl.class);
	@Inject ResourceManager _resourceMgr;
	@Inject PhysicalNetworkDao _physicalNetworkDao;
	@Inject PhysicalNetworkServiceProviderDao _physicalNetworkServiceProviderDao;
	@Inject HostDetailsDao _hostDetailsDao;
	
	
	@Override
	public boolean prepare(VirtualMachineProfile<UserVmVO> profile, DeployDestination dest, ReservationContext context) {
		List<NicProfile> nics = profile.getNics();
	    if (nics.size() == 0) {
	    	throw new CloudRuntimeException("Cannot do PXE start without nic");
	    }
	   
	    SearchCriteriaService<BaremetalPxeVO, BaremetalPxeVO> sc = SearchCriteria2.create(BaremetalPxeVO.class);
        sc.addAnd(sc.getEntity().getDeviceType(), Op.EQ, BaremetalPxeType.PING.toString());
        sc.addAnd(sc.getEntity().getPodId(), Op.EQ, dest.getPod().getId());
        BaremetalPxeVO pxeVo = sc.find();
        if (pxeVo == null) {
            throw new CloudRuntimeException("No PING PXE server found in pod: " + dest.getPod().getId() + ", you need to add it before starting VM");
        }
        long pxeServerId = pxeVo.getHostId();
        
		NicProfile pxeNic = nics.get(0);
	    String mac = pxeNic.getMacAddress();
	    String ip = pxeNic.getIp4Address();
	    String gateway = pxeNic.getGateway();
	    String mask = pxeNic.getNetmask();
	    String dns = pxeNic.getDns1();
	    if (dns == null) {
	    	dns = pxeNic.getDns2();
	    }

		try {
			String tpl = profile.getTemplate().getUrl();
			assert tpl != null : "How can a null template get here!!!";
			PreparePxeServerCommand cmd = new PreparePxeServerCommand(ip, mac, mask, gateway, dns, tpl,
					profile.getVirtualMachine().getInstanceName(), dest.getHost().getName());
			PreparePxeServerAnswer ans = (PreparePxeServerAnswer) _agentMgr.send(pxeServerId, cmd);
			if (!ans.getResult()) {
				s_logger.warn("Unable tot program PXE server: " + pxeVo.getId() + " because " + ans.getDetails());
				return false;
			}
			
			IpmISetBootDevCommand bootCmd = new IpmISetBootDevCommand(BootDev.pxe);
			Answer anw = _agentMgr.send(dest.getHost().getId(), bootCmd);
			if (!anw.getResult()) {
				s_logger.warn("Unable to set host: " + dest.getHost().getId() + " to PXE boot because " + anw.getDetails());
			}
			
			return anw.getResult();
		} catch (Exception e) {
			s_logger.warn("Cannot prepare PXE server", e);
			return false;
		}
	}

	
    @Override
    public boolean prepareCreateTemplate(Long pxeServerId, UserVm vm, String templateUrl) {        
        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        if (nics.size() != 1) {
            throw new CloudRuntimeException("Wrong nic number " + nics.size() + " of vm " + vm.getId());
        }
        
        /* use last host id when VM stopped */
        Long hostId = (vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId());
        HostVO host = _hostDao.findById(hostId);
        DataCenterVO dc = _dcDao.findById(host.getDataCenterId());
        NicVO nic = nics.get(0);
        String mask = nic.getNetmask();
        String mac = nic.getMacAddress();
        String ip = nic.getIp4Address();
        String gateway = nic.getGateway();
        String dns = dc.getDns1();
        if (dns == null) {
            dns = dc.getDns2();
        }
        
        try {
            prepareCreateTemplateCommand cmd = new prepareCreateTemplateCommand(ip, mac, mask, gateway, dns, templateUrl);
            Answer ans = _agentMgr.send(pxeServerId, cmd);
            return ans.getResult();
        } catch (Exception e) {
            s_logger.debug("Prepare for creating baremetal template failed", e);
            return false;
        }
    }


    @Override
    @DB
    public BaremetalPxeVO addPxeServer(AddBaremetalPxeCmd cmd) {
        AddBaremetalPxePingServerCmd pcmd = (AddBaremetalPxePingServerCmd)cmd;
        
        PhysicalNetworkVO pNetwork = null;
        long zoneId;
        
        if (cmd.getPhysicalNetworkId() == null || cmd.getUrl() == null || cmd.getUsername() == null || cmd.getPassword() == null) {
            throw new InvalidParameterValueException("At least one of the required parameters(physical network id, url, username, password) is null");
        } 
        
        pNetwork = _physicalNetworkDao.findById(cmd.getPhysicalNetworkId());
        if (pNetwork == null) {
            throw new InvalidParameterValueException("Could not find phyical network with ID: " + cmd.getPhysicalNetworkId());
        }
        zoneId = pNetwork.getDataCenterId();
        
        NetworkDevice ntwkDevice = NetworkDevice.PxeServer;
        PhysicalNetworkServiceProviderVO ntwkSvcProvider = _physicalNetworkServiceProviderDao.findByServiceProvider(pNetwork.getId(), ntwkDevice.getNetworkServiceProvder());
        if (ntwkSvcProvider == null) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkDevice.getNetworkServiceProvder() +
                    " is not enabled in the physical network: " + cmd.getPhysicalNetworkId() + "to add this device");
        } else if (ntwkSvcProvider.getState() == PhysicalNetworkServiceProvider.State.Shutdown) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkSvcProvider.getProviderName() +
                    " is in shutdown state in the physical network: " + cmd.getPhysicalNetworkId() + "to add this device");
        }
        
        HostPodVO pod = _podDao.findById(cmd.getPodId());
        if (pod == null) {
            throw new InvalidParameterValueException("Could not find pod with ID: " + cmd.getPodId());
        } 
        
        List<HostVO> pxes = _resourceMgr.listAllUpAndEnabledHosts(Host.Type.PxeServer, null, cmd.getPodId(), zoneId);
        if (pxes.size() != 0) {
            throw new InvalidParameterValueException("Already had a PXE server in Pod: " + cmd.getPodId() + " zone: " + zoneId);
        }
        
        String storageServerIp = pcmd.getPingStorageServerIp();
        if (storageServerIp == null) {
            throw new InvalidParameterValueException("No IP for storage server specified");
        }
        String pingDir = pcmd.getPingDir();
        if (pingDir == null) {
            throw new InvalidParameterValueException("No direcotry for storage server specified");
        }
        String tftpDir = pcmd.getTftpDir();
        if (tftpDir == null) {
            throw new InvalidParameterValueException("No TFTP directory specified");
        }
        
        String cifsUsername = pcmd.getPingStorageServerUserName();
        if (cifsUsername == null || cifsUsername.equalsIgnoreCase("")) {
            cifsUsername = "xxx";
        }
        String cifsPassword = pcmd.getPingStorageServerPassword();
        if (cifsPassword == null || cifsPassword.equalsIgnoreCase("")) {
            cifsPassword = "xxx";
        }
        
        
        URI uri;
        try {
            uri = new URI(cmd.getUrl());
        } catch (Exception e) {
            s_logger.debug(e);
            throw new InvalidParameterValueException(e.getMessage());
        }
        String ipAddress = uri.getHost();
        
        String guid = getPxeServerGuid(Long.toString(zoneId)  + "-" + pod.getId(), BaremetalPxeType.PING.toString(), ipAddress);
        
        ServerResource resource = null;
        Map params = new HashMap<String, String>();
        params.put(BaremetalPxeService.PXE_PARAM_ZONE, BaremetalPxeType.PING.toString());
        params.put(BaremetalPxeService.PXE_PARAM_ZONE, Long.toString(zoneId));
        params.put(BaremetalPxeService.PXE_PARAM_POD, String.valueOf(pod.getId()));
        params.put(BaremetalPxeService.PXE_PARAM_IP, ipAddress);
        params.put(BaremetalPxeService.PXE_PARAM_USERNAME, cmd.getUsername());
        params.put(BaremetalPxeService.PXE_PARAM_PASSWORD, cmd.getPassword());
        params.put(BaremetalPxeService.PXE_PARAM_PING_STORAGE_SERVER_IP, storageServerIp);
        params.put(BaremetalPxeService.PXE_PARAM_PING_ROOT_DIR, pingDir);
        params.put(BaremetalPxeService.PXE_PARAM_TFTP_DIR, tftpDir);
        params.put(BaremetalPxeService.PXE_PARAM_PING_STORAGE_SERVER_USERNAME, cifsUsername);
        params.put(BaremetalPxeService.PXE_PARAM_PING_STORAGE_SERVER_PASSWORD, cifsPassword);
        params.put(BaremetalPxeService.PXE_PARAM_GUID, guid);
        
        resource = new BaremetalPingPxeResource();
        try {
            resource.configure("PING PXE resource", params);
        } catch (Exception e) {
            s_logger.debug(e);
            throw new CloudRuntimeException(e.getMessage());
        }
        
        Host pxeServer = _resourceMgr.addHost(zoneId, resource, Host.Type.PxeServer, params);
        if (pxeServer == null) {
            throw new CloudRuntimeException("Cannot add PXE server as a host");
        }
        
        BaremetalPxeVO vo = new BaremetalPxeVO();
        Transaction txn = Transaction.currentTxn();
        vo.setHostId(pxeServer.getId());
        vo.setNetworkServiceProviderId(ntwkSvcProvider.getId());
        vo.setPodId(pod.getId());
        vo.setPhysicalNetworkId(pcmd.getPhysicalNetworkId());
        vo.setDeviceType(BaremetalPxeType.PING.toString());
        txn.commit();
        return vo;
    }

    @Override
    public BaremetalPxeResponse getApiResponse(BaremetalPxeVO vo) {
        BaremetalPxePingResponse response = new BaremetalPxePingResponse();
        response.setId(vo.getId());
        response.setPhysicalNetworkId(vo.getPhysicalNetworkId());
        response.setPodId(vo.getPodId());
        Map<String, String> details = _hostDetailsDao.findDetails(vo.getHostId());
        response.setPingStorageServerIp(details.get(BaremetalPxeService.PXE_PARAM_PING_STORAGE_SERVER_IP));
        response.setPingDir(details.get(BaremetalPxeService.PXE_PARAM_PING_ROOT_DIR));
        response.setTftpDir(details.get(BaremetalPxeService.PXE_PARAM_TFTP_DIR));
        return response;
    }


    @Override
    public List<BaremetalPxeResponse> listPxeServers(ListBaremetalPxePingServersCmd cmd) {
        SearchCriteriaService<BaremetalPxeVO, BaremetalPxeVO> sc = SearchCriteria2.create(BaremetalPxeVO.class);
        sc.addAnd(sc.getEntity().getDeviceType(), Op.EQ, BaremetalPxeType.PING.toString());
        if (cmd.getPodId() != null) {
            sc.addAnd(sc.getEntity().getPodId(), Op.EQ, cmd.getPodId());
            if (cmd.getId() != null) {
                sc.addAnd(sc.getEntity().getId(), Op.EQ, cmd.getId());
            }
        }
        List<BaremetalPxeVO> vos = sc.list();
        List<BaremetalPxeResponse> responses = new ArrayList<BaremetalPxeResponse>(vos.size());
        for (BaremetalPxeVO vo : vos) {
            responses.add(getApiResponse(vo));
        }
        return responses;
    }
}
