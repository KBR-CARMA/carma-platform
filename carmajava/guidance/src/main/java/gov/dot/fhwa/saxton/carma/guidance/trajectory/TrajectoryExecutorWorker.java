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

package gov.dot.fhwa.saxton.carma.guidance.trajectory;

import org.apache.commons.logging.Log;
import gov.dot.fhwa.saxton.carma.guidance.GuidanceCommands;
import gov.dot.fhwa.saxton.carma.guidance.trajectory.IManeuver.ManeuverType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guidance package TrajectoryExecutorWorker
 * <p>
 * Performs all the non-ROS functionality of the TrajectoryWorker Guidance component.
 * Handles the execution and management of planned trajectories.
 */
public class TrajectoryExecutorWorker {
  protected GuidanceCommands commands;
  protected List<PctCallback> callbacks = new ArrayList<>();
  protected double downtrackDistance = 0.0;
  protected Trajectory currentTrajectory = null;
  protected Trajectory nextTrajectory = null;
  protected IManeuver currentLateralManeuver = null;
  protected IManeuver currentLongitudinalManeuver = null;

  // Storage struct for internal representation of callbacks based on trajectory completion percent
  private class PctCallback {
    boolean called = false;
    double pct;
    OnTrajectoryProgressCallback callback;

    PctCallback(double pct, OnTrajectoryProgressCallback callback) {
      this.pct = pct;
      this.callback = callback;
    }
  }

  public TrajectoryExecutorWorker(GuidanceCommands commands) {
    this.commands = commands;
  }

  /**
   * Check the status of the current maneuvers and start them if the need to be started
   */
  private void checkAndStartManeuvers() {
    // Start the maneuvers if they aren't already running
    if (currentLateralManeuver != null 
    && downtrackDistance >= currentLateralManeuver.getStartLocation() 
    && !currentLateralManeuver.isRunning()) {
      currentLateralManeuver.execute();
    }

    if (currentLongitudinalManeuver != null 
    && downtrackDistance >= currentLongitudinalManeuver.getStartLocation() 
    && !currentLongitudinalManeuver.isRunning()) {
      currentLongitudinalManeuver.execute();
    }
  }

  /**
   * Update the TrajectoryExecutors current downtrack distance with respect to the route
   * </p>
   * Also triggers changes of maneuvers and/or trajectory and any callbacks that may be needed
   * as a result of the new trajectory completion value. This is the main event-driven method
   * for this class.
   * 
   * @param downtrack The current downtrack distance from RouteState
   */
  public void updateDowntrackDistance(double downtrack) {
    this.downtrackDistance = downtrack;

    checkAndStartManeuvers();

    // Call necessary callbacks
    double completePct = getTrajectoryCompletionPct();
    for (PctCallback callback : callbacks) {
      if (!callback.called && completePct >= callback.pct) {
        callback.callback.onProgress(completePct);
        callback.called = true;
      }
    }

    // If we've finished executing a maneuver, move onto the next one
    if (currentLateralManeuver != null && downtrackDistance >= currentLateralManeuver.getEndLocation()) {
      currentLateralManeuver.halt();
      currentLateralManeuver = currentTrajectory.getManeuverAt(currentLateralManeuver.getEndLocation(), ManeuverType.LATERAL);

      if (currentLateralManeuver != null 
      && downtrackDistance >= currentLateralManeuver.getStartLocation() 
      && !currentLateralManeuver.isRunning()) {
        currentLateralManeuver.execute();
      }
    }
    if (currentLongitudinalManeuver != null && downtrackDistance >= currentLongitudinalManeuver.getEndLocation()) {
      currentLongitudinalManeuver.halt();
      currentLongitudinalManeuver = currentTrajectory.getManeuverAt(currentLongitudinalManeuver.getEndLocation(), ManeuverType.LONGITUDINAL);

      if (currentLongitudinalManeuver != null 
      && downtrackDistance >= currentLongitudinalManeuver.getStartLocation() 
      && !currentLongitudinalManeuver.isRunning()) {
        currentLongitudinalManeuver.execute();
      }
    }

    // See if we need to swap to our queued trajectory
    if (currentLateralManeuver == null && currentLongitudinalManeuver == null) {
      swapTrajectories();
      checkAndStartManeuvers();
    }
  }

  /**
   * Abort the current and queued trajectories and the currently executing maneuvers
   */
  public void abortTrajectory() {
    currentTrajectory = null;
    nextTrajectory = null;

    if (currentLateralManeuver != null) {
      if (currentLateralManeuver.isRunning()) {
        currentLateralManeuver.halt();
      }

      currentLateralManeuver = null;
    }

    if (currentLongitudinalManeuver != null) {
      if (currentLongitudinalManeuver.isRunning()) {
        currentLongitudinalManeuver.halt();
      }

      currentLongitudinalManeuver = null;
    }
  }

  /**
   * Get the current lateral maneuver, null if none are currently executing
   */
  public IManeuver getCurrentLateralManeuver() {
    return currentLateralManeuver;
  }

  /**
   * Get the current longitudinal maneuver, null if none are currently executing
   */
  public IManeuver getCurrentLongitudinalManeuver() {
    return currentLongitudinalManeuver;
  }

  /**
   * Get the next lateral maneuver, null if none are currently executing
   */
  public IManeuver getNextLateralManeuver() {
    return currentTrajectory.getNextManeuverAfter(downtrackDistance, ManeuverType.LATERAL);
  }

  /**
   * Get the next longitudinal maneuver, null if none are currently executing
   */
  public IManeuver getNextLongitudinalManeuver() {
    return currentTrajectory.getNextManeuverAfter(downtrackDistance, ManeuverType.LONGITUDINAL);
  }

  /**
   * Get the current complection pct of the trajectory, -1.0 if a trajectory isn't currently executing
   * <p>
   * Percent completion is defined over [0, 1] U (-1.0)
   */
  public double getTrajectoryCompletionPct() {
    // Handle the case where we're not running a trajectory yet
    if (currentTrajectory == null) {
      return -1.0;
    }

    double numerator = downtrackDistance - currentTrajectory.getStartLocation();
    double denominator = currentTrajectory.getEndLocation() - currentTrajectory.getStartLocation();
    return numerator / denominator;
  }

  /**
   * Register a callback function to be invoked when the specified percent completion is acheived
   * <p>
   * Percent completion is defined over [0, 1] U (-1.0)
   */
  public void registerOnTrajectoryProgressCallback(double pct, OnTrajectoryProgressCallback callback) {
    callbacks.add(new PctCallback(pct, callback));
  }

  /**
   * Swap execution of the current and queued trajectory to allow to seamless handoff without entering a
   * failure mode.
   */
  private void swapTrajectories() {
    // If we don't have any trajectories to swap, just exit
    if (nextTrajectory == null) {
      currentTrajectory = null;
      return;
    }

    currentTrajectory = nextTrajectory;
    nextTrajectory = null;

    if (currentTrajectory != null) {
      currentLateralManeuver = currentTrajectory.getManeuverAt(downtrackDistance, ManeuverType.LATERAL);
      currentLongitudinalManeuver = currentTrajectory.getManeuverAt(downtrackDistance, ManeuverType.LONGITUDINAL);
    }

     for (PctCallback callback : callbacks) {
       callback.called = false;
     }
  }

  /**
   * Submit the specified trajectory for execution
   * </p>
   * If no trajectories are running it will be run right away, otherwise it will be queued to run after the current
   * trajectory finishes execution.
   */
  public void runTrajectory(Trajectory traj) {
    if (currentTrajectory == null) {
      this.currentTrajectory = traj;
      this.currentLateralManeuver = traj.getManeuverAt(downtrackDistance, ManeuverType.LATERAL);
      this.currentLongitudinalManeuver = traj.getManeuverAt(downtrackDistance, ManeuverType.LONGITUDINAL);
      for (PctCallback callback : callbacks) {
        callback.called = false;
      }
    } else {
      // Hold onto this trajectory for double buffering, flip to it when we finish trajectory
      nextTrajectory = traj;
    }
  }
}
