/*
 * TODO: Copyright (C) 2017 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.mock_drivers;

import org.apache.commons.logging.Log;
import cav_srvs.BindRequest;
import cav_srvs.BindResponse;
import cav_srvs.GetAPISpecificationRequest;
import cav_srvs.GetAPISpecificationResponse;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.service.ServiceResponseBuilder;
import org.ros.node.service.ServiceServer;
import org.ros.node.topic.Publisher;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Abstract implementation of a simulated driver. Reads a simulated data file and publishes the data.
 */
public abstract class AbstractMockDriver implements IMockDriver {
  protected final ConnectedNode connectedNode;

  protected final Log log;
  protected final ParameterTree params;

  // Parameters
  protected final String rosRunID;
  protected final String dataFilePath;

  // Topics
  // Published
  protected final Publisher<bond.Status> bondPub;
  protected final Publisher<cav_msgs.DriverStatus> discoveryPub;

  // Server
  protected final ServiceServer<cav_srvs.BindRequest, cav_srvs.BindResponse> bindService;
  protected final ServiceServer<GetAPISpecificationRequest, GetAPISpecificationResponse>
    getApiService;

  protected final String delimiter = ","; // Comma for csv file
  protected RandomAccessFile reader = null;
  protected byte driverStatus = cav_msgs.DriverStatus.OFF;

  /**
   * Constructor establishes the publishers and subscribers for the ROS network.
   *
   * @param connectedNode the ros node which this driver provides implementations for
   */
  public AbstractMockDriver(ConnectedNode connectedNode) {
    this.connectedNode = connectedNode;
    log = connectedNode.getLog();
    params = connectedNode.getParameterTree();

    // Parameters
    rosRunID = params.getString("/run_id");
    dataFilePath = params.getString("~/data_file_path");

    // Topics
    // Published
    bondPub = connectedNode.newPublisher("~/bond", bond.Status._TYPE);
    discoveryPub = connectedNode.newPublisher("driver_discovery", cav_msgs.DriverStatus._TYPE);

    // Service
    // Server
    bindService = connectedNode.newServiceServer("~/bind", cav_srvs.Bind._TYPE,
      new ServiceResponseBuilder<BindRequest, BindResponse>() {
        @Override public void build(cav_srvs.BindRequest request, cav_srvs.BindResponse response) {
          log.info("Request for bind received");
        }
      });
    getApiService = connectedNode
      .newServiceServer("~/get_driver_api", cav_srvs.GetAPISpecification._TYPE,
        new ServiceResponseBuilder<cav_srvs.GetAPISpecificationRequest, cav_srvs.GetAPISpecificationResponse>() {
          @Override public void build(cav_srvs.GetAPISpecificationRequest request,
            cav_srvs.GetAPISpecificationResponse response) {
            response.setApiList(getDriverAPI());
          }
        });
  }

  @Override public abstract GraphName getDefaultDriverName();

  @Override public void onStart(ConnectedNode connectedNode) {
    // Open a data file and set the default driver status
    try {
      reader = new RandomAccessFile(dataFilePath, "r");
      driverStatus = cav_msgs.DriverStatus.OPERATIONAL;
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      log
        .warn(getDefaultDriverName() + " could not find file " + dataFilePath + ".No data published");
      driverStatus = cav_msgs.DriverStatus.DEGRADED;
    }
  }

  @Override public void onShutdown(Node node) {
    // Close an opened data file
    closeDataFile();
  }

  @Override public void readAndPublishData() {
    // Read each line from a data file and publish that data.
    // On failure to read a line of data all publishing
    String dataLine;
    if (reader != null) {
      try {
        dataLine = reader.readLine();
        if (dataLine != null) {
          // separate on delimiter
          String[] data = dataLine.split(delimiter);
          try {
            publishData(data);
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
            // Log warning if a line data incorrect
            log.warn(getDefaultDriverName()
              + " read data file with incorrect number of columns. Desired column count is: "
              + getExpectedRowCount() + " No data published.");
            driverStatus = cav_msgs.DriverStatus.DEGRADED;
          }
        } else {
          reader.seek(0); // Loop over file while the node is running.
        }
      } catch (IOException e) {
        e.printStackTrace();
        closeDataFile();
        reader = null;
        // Log warning if the node failed to read data in the file. All publishing will be stopped in this case as the file may be corrupt.
        log.warn(getDefaultDriverName() + " failed to read data file. No data will be published");
        driverStatus = cav_msgs.DriverStatus.FAULT;
      }
    }
  }

  @Override public void publishDriverStatus() {
    cav_msgs.DriverStatus driverStatusMsg = discoveryPub.newMessage();
    driverStatusMsg.setName(getDefaultDriverName().toString());
    driverStatusMsg.setStatus(driverStatus);
    driverStatusMsg.setCanBus(false);
    driverStatusMsg.setSensor(false);
    driverStatusMsg.setPosition(false);
    driverStatusMsg.setComms(false);
    driverStatusMsg.setController(false);

    for (String driverType: getDriverTypesList()) {
      switch (driverType) {
        case "can_bus":
          driverStatusMsg.setCanBus(true);
          break;
        case "sensor":
          driverStatusMsg.setSensor(true);
          break;
        case "position":
          driverStatusMsg.setPosition(true);
          break;
        case "comms":
          driverStatusMsg.setComms(true);
          break;
        case "controller":
          driverStatusMsg.setController(true);
          break;
      }
    }
    discoveryPub.publish(driverStatusMsg);
  }

  /**
   * Safely closes the opened data file
   */
  protected void closeDataFile() {
    if (reader != null) {
      try {
        reader.close();
      } catch (IOException ex) {
        ex.printStackTrace();
      }
    }
  }

  /**
   * Publishes the provided data array
   * @param data The data to be published usually provided as a direct line from a data file
   * @throws IllegalArgumentException Exception thrown when the number of data elements does not match getExpectedRowCount()
   */
  protected abstract void publishData(String[] data) throws IllegalArgumentException;

  /**
   * Gets the expected number of row elements in a data line
   * @return The number of expected elements
   */
  protected abstract int getExpectedRowCount();

  /**
   * Gets a list of driver information which this driver can provide (can, comms, sensor, position, controller)
   * @return The list of driver types which this driver satisfies
   */
  protected abstract List<String> getDriverTypesList();

  public abstract List<String> getDriverAPI();
}



