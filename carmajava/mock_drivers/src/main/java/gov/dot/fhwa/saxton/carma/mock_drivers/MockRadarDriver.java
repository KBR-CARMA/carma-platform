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

import cav_msgs.ByteArray;
import org.jboss.netty.buffer.ChannelBuffers;
import org.ros.message.MessageFactory;
import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.NodeConfiguration;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A class which can be used to simulate an Arada comms driver for the CarmaPlatform.
 * <p>
 * Command line test:
 * ROSJava does not support rosrun parameter setting so a rosrun is a multi step process
 * rosparam set /mock_driver/simulated_driver 'arada'
 * rosparam set /mock_driver/data_file_path '/home/username/temp.csv'
 * rosrun carmajava mock_drivers gov.dot.fhwa.saxton.carma.mock_drivers.MockDriverNode
 */
public class MockRadarDriver extends AbstractMockDriver {

  NodeConfiguration nodeConfiguration = NodeConfiguration.newPrivate();
  MessageFactory messageFactory = nodeConfiguration.getTopicMessageFactory();
  int sequenceNumber = 0;

  // Topics
  // Published
  final Publisher<cav_msgs.ExternalObjectList> objectPub;

  // Published	Parameter	~/aoi_angle
  // Published	Parameter	~/device_port
  // Published	Parameter	~/min_width
  // Published	Parameter	~/timeout

  final int expectedDataRowCount = 2;

  public MockRadarDriver(ConnectedNode connectedNode) {
    super(connectedNode);
    // Topics
    // Published
    objectPub = connectedNode.newPublisher("~/sensor/tracked_objects", cav_msgs.ExternalObjectList._TYPE);
  }

  @Override public GraphName getDefaultDriverName() {
    return GraphName.of("mock_radar_driver");
  }

  @Override protected void publishData(String[] data) throws IllegalArgumentException {
    if (data.length != expectedDataRowCount) {
      sequenceNumber++;
      throw new IllegalArgumentException(
        "Publish data called for MockAradaDriver with incorrect number of data elements. "
          + "The required number of data elements is " + expectedDataRowCount);
    }

    // Make messages
    cav_msgs.ExternalObjectList objectListMsg = objectPub.newMessage();
    cav_msgs.ExternalObject externalObject = messageFactory.newFromType(cav_msgs.ExternalObject._TYPE);;

    std_msgs.Header hdr = messageFactory.newFromType(std_msgs.Header._TYPE);
    hdr.setFrameId("0");
    hdr.setSeq(sequenceNumber);
    hdr.setStamp(connectedNode.getCurrentTime());

    //TODO: Due to the undefined number of objects detected I will need to overload the readAndPublishData function at a minimum and maybe onStart

    externalObject.setHeader(hdr);
    objectListMsg.setObjects();

    recvMsg.setHeader(hdr);
    recvMsg.setMessageType(data[0]);

    byte[] rawBytes = (data[1].getBytes());
    // It seems that the ros messages byte[] is LittleEndian. Using BigEndian results in a IllegalArgumentException
    recvMsg.setContent(ChannelBuffers.copiedBuffer(ByteOrder.LITTLE_ENDIAN, rawBytes));

    // Publish Data
    recvPub.publish(recvMsg);
    sequenceNumber++;
  }

  @Override protected int getExpectedRowCount() {
    return expectedDataRowCount;
  }

  @Override protected List<String> getDriverTypesList() {
    return new ArrayList<>(Arrays.asList("comms"));
  }

  @Override public List<String> getDriverAPI(){
    return new ArrayList<>(Arrays.asList(
      connectedNode.getName() + "~/sensor/tracked_objects"
    ));
  }
}
