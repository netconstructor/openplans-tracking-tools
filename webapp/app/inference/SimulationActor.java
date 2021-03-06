package inference;

import inference.InferenceService.INFO_LEVEL;
import models.InferenceInstance;

import org.openplans.tools.tracking.impl.Simulation;
import org.openplans.tools.tracking.impl.VehicleState;

import play.Logger;

import akka.actor.UntypedActor;

public class SimulationActor extends UntypedActor {
  
  private InferenceInstance instance;
  private Simulation sim;

  @Override
  public void onReceive(Object arg0) throws Exception {
    
    
    // TODO this is a lame way to get concurrency. fix this
    final Simulation sim = (Simulation) arg0;

    // TODO info level should be a parameter
    this.instance = InferenceService.getOrCreateInferenceInstance(
        sim.getSimulationName(), true, INFO_LEVEL.DEBUG);

    this.instance.simSeed = sim.getSeed();
    this.instance.totalRecords = (int) ((sim.getSimParameters().getEndTime()
        .getTime() - sim.getSimParameters().getStartTime().getTime()) / (sim.getSimParameters()
        .getFrequency() * 1000d)); 
    this.sim = sim;
    
    Logger.info("starting simulation with seed = " + sim.getSeed());
    
    runSimulation();
  }
  
  public void runSimulation() {
    VehicleState vehicleState = this.sim.computeInitialState();
    long time = this.sim.getSimParameters().getStartTime().getTime();
    
    while (time < this.sim.getSimParameters().getEndTime().getTime()
        && InferenceService.getInferenceInstance(sim.getSimulationName()) != null) {
      vehicleState = this.sim.stepSimulation(vehicleState);
      time = vehicleState.getObservation().getTimestamp().getTime();
      this.instance.update(
          vehicleState, vehicleState.getObservation(), 
          this.sim.getSimParameters().isPerformInference());
    }
    
    if (this.instance.getRecordsProcessed() > 0)
      Logger.info("avg. records per sec = " + 1000d
          / instance.getAverager().getMean().value);
    
    if (InferenceService.getInferenceInstance(sim.getSimulationName()) != null) {
      this.instance = null;
      this.sim = null;
    }
    
  }
  
  public InferenceInstance getInstance() {
    return instance;
  }
  
}