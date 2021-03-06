package org.openplans.tools.tracking.impl.statistics;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.ParticleFilter;
import gov.sandia.cognition.statistics.distribution.DefaultDataDistribution;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang.NotImplementedException;
import org.openplans.tools.tracking.impl.LogDefaultDataDistribution;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.VehicleState;
import org.openplans.tools.tracking.impl.VehicleState.VehicleStateInitialParameters;
import org.openplans.tools.tracking.impl.VehicleStateConditionalParams;
import org.openplans.tools.tracking.impl.graph.InferredEdge;
import org.openplans.tools.tracking.impl.graph.paths.InferredPath;
import org.openplans.tools.tracking.impl.graph.paths.InferredPathEntry;
import org.openplans.tools.tracking.impl.graph.paths.PathEdge;
import org.openplans.tools.tracking.impl.util.OtpGraph;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class VehicleTrackingPathSamplerFilterUpdater implements
    ParticleFilter.Updater<Observation, VehicleState> {

  private static class UpdaterThreadLocal extends ThreadLocal<Random> {

    private long seed;

    public UpdaterThreadLocal(long seed) {
      super();
      this.seed = seed;
    }

    @Override
    public Random get() {
      return super.get();
    }

    public long getSeed() {
      return seed;
    }

    @Override
    protected Random initialValue() {
      final Random rng = new Random();
      if (this.seed == 0l) {
        this.seed = rng.nextLong();
      }
      rng.setSeed(this.seed);

      return rng;
    }

    public void setSeed(long nextLong) {
      this.seed = nextLong;
    }

  }

  private static final long serialVersionUID = 2884138088944317656L;
  private final Observation initialObservation;

  private final OtpGraph inferredGraph;

  private final VehicleStateInitialParameters parameters;

  private final UpdaterThreadLocal threadRandom;

  public VehicleTrackingPathSamplerFilterUpdater(Observation obs,
    OtpGraph inferredGraph, VehicleStateInitialParameters parameters) {
    this.initialObservation = obs;
    this.inferredGraph = inferredGraph;
    this.parameters = parameters;
    this.threadRandom = new UpdaterThreadLocal(parameters.getSeed());
  }

  @Override
  public VehicleTrackingPathSamplerFilterUpdater clone() {
    throw new RuntimeException("not implemented");
  }

  /**
   * Evaluate the "point-wise" likelihood, i.e. we don't evaluate a path.
   */
  @Override
  public double computeLogLikelihood(VehicleState particle,
    Observation observation) {
    throw new NotImplementedException();
    //    return particle.getProbabilityFunction().logEvaluate(
    //        new VehicleStateConditionalParams(PathEdge.getEdge(
    //            particle.getInferredEdge(), 0d), observation
    //            .getProjectedPoint(), 0d));
  }

  @Override
  public DataDistribution<VehicleState> createInitialParticles(
    int numParticles) {
    /*
     * Create initial distributions for all snapped edges
     */

    // final List<StreetEdge> initialEdges = inferredGraph.getNearbyEdges(null)
    // .getNarratedGraph().snapToGraph(
    // null, initialObservation.getObsCoords());

    final StandardRoadTrackingFilter trackingFilter = new StandardRoadTrackingFilter(
        parameters.getObsVariance(),
        parameters.getOffRoadStateVariance(),
        parameters.getOnRoadStateVariance());
    final MultivariateGaussian initialBelief = trackingFilter
        .createInitialLearnedObject();
    final Vector xyPoint = initialObservation.getProjectedPoint();
    initialBelief.setMean(VectorFactory.getDefault().copyArray(
        new double[] { xyPoint.getElement(0), 0d,
            xyPoint.getElement(1), 0d }));
    final List<StreetEdge> initialEdges = inferredGraph
        .getNearbyEdges(initialBelief, trackingFilter);

    final DataDistribution<VehicleState> initialDist = new DefaultDataDistribution<VehicleState>(
        numParticles);

    final Set<InferredPathEntry> evaluatedPaths = Sets.newHashSet();
    if (!initialEdges.isEmpty()) {
      for (final Edge nativeEdge : initialEdges) {
        final InferredEdge edge = inferredGraph
            .getInferredEdge(nativeEdge);
        final PathEdge pathEdge = PathEdge.getEdge(edge, 0d);
        final InferredPath path = InferredPath
            .getInferredPath(pathEdge);
        evaluatedPaths.add(new InferredPathEntry(
            path, null, null, null, Double.NEGATIVE_INFINITY));

        final VehicleState state = new VehicleState(
            this.inferredGraph, initialObservation,
            pathEdge.getInferredEdge(), parameters,
            this.threadRandom.get());

        final VehicleStateConditionalParams edgeLoc = new VehicleStateConditionalParams(
            pathEdge, initialObservation.getProjectedPoint());
        final double lik = state.getProbabilityFunction().evaluate(
            edgeLoc);

        initialDist.increment(state, lik);
      }
    }

    /*
     * Free-motion
     */
    final VehicleState state = new VehicleState(
        this.inferredGraph, initialObservation,
        InferredEdge.getEmptyEdge(), parameters,
        this.threadRandom.get());

    final double lik = state.getProbabilityFunction().evaluate(
        new VehicleStateConditionalParams(initialObservation
            .getProjectedPoint()));

    initialDist.increment(state, lik);

    final DataDistribution<VehicleState> retDist = new LogDefaultDataDistribution<VehicleState>(
        initialDist.sample(threadRandom.get(), numParticles));

    return retDist;
  }

  public Observation getInitialObservation() {
    return initialObservation;
  }

  public ThreadLocal<Random> getThreadRandom() {
    return threadRandom;
  }

  private InferredPath traverseEdge(
    EdgeTransitionDistributions edgeTransDist,
    final MultivariateGaussian belief, PathEdge startEdge,
    StandardRoadTrackingFilter movementFilter) {

    /*
     * We project the road path
     */
    final Random rng = this.threadRandom.get();
    rng.setSeed(this.threadRandom.getSeed());
    PathEdge currentEdge = startEdge;
    PathEdge previousEdge = null;
    final MultivariateGaussian newBelief = belief.clone();

    final List<PathEdge> currentPath = Lists.newArrayList();

    double distTraveled = 0d;
    Double totalDistToTravel = null;
    while (totalDistToTravel == null ||
    // the following case is when we're truly on the edge
        Math.abs(totalDistToTravel) > Math.abs(distTraveled)) {

      final List<InferredEdge> transferEdges = Lists.newArrayList();
      if (currentEdge.getInferredEdge() == InferredEdge
          .getEmptyEdge()) {
        final Vector projLocation = StandardRoadTrackingFilter
            .getOg().times(newBelief.getMean());
        for (final StreetEdge edge : this.inferredGraph
            .getNearbyEdges(
                projLocation,
                movementFilter.getObservationErrorAbsRadius())) {
          transferEdges.add(this.inferredGraph.getInferredEdge(edge));
        }
      } else {
        if (totalDistToTravel == null) {
          transferEdges.add(startEdge.getInferredEdge());
        } else {
          if (newBelief.getMean().getElement(0) < 0d) {
            transferEdges.addAll(currentEdge.getInferredEdge()
                .getIncomingTransferableEdges());
          } else if (newBelief.getMean().getElement(0) > 0d) {
            transferEdges.addAll(currentEdge.getInferredEdge()
                .getOutgoingTransferableEdges());
          } else {
            transferEdges.addAll(currentEdge.getInferredEdge()
                .getIncomingTransferableEdges());
            transferEdges.addAll(currentEdge.getInferredEdge()
                .getOutgoingTransferableEdges());
          }
          // Make sure we don't move back and forth
          transferEdges.remove(currentEdge.getInferredEdge());
        }
      }

      final InferredEdge sampledEdge = edgeTransDist.sample(
          rng, transferEdges,
          currentEdge == null ? startEdge.getInferredEdge()
              : currentEdge.getInferredEdge());

      if (sampledEdge == InferredEdge.getEmptyEdge()) {

        if (totalDistToTravel == null) {
          /*
           * Off-road, so just return/add the empty path and be done
           */
          movementFilter.predict(
              newBelief, PathEdge.getEmptyPathEdge(), startEdge);
        } else {
          /*
           * This belief should/could extend past the length of the current
           * edge, so that the converted ground coordinates emulate driving
           * off of a road (most of the time, perhaps).
           */
          StandardRoadTrackingFilter.convertToGroundBelief(
              newBelief, currentEdge, true);
        }

        currentEdge = PathEdge.getEmptyPathEdge();
        currentPath.add(PathEdge.getEmptyPathEdge());
        break;
      }

      double direction = newBelief.getMean().getElement(0) >= 0d ? 1d
          : -1d;
      final PathEdge sampledPathEdge = PathEdge.getEdge(
          sampledEdge,
          previousEdge == null || previousEdge.isEmptyEdge() ? 0d
              : direction
                  * previousEdge.getInferredEdge().getLength()
                  + previousEdge.getDistToStartOfEdge());

      if (sampledPathEdge == null) {
        /*-
         * We have nowhere else to go, but we're not moving off of an edge, so 
         * we call this a stop.
         */
        newBelief.getMean().setElement(
            0, direction * currentEdge.getInferredEdge().getLength());
        newBelief.getMean().setElement(1, 0d);
        break;
      }

      if (totalDistToTravel == null) {
        /*
         * Predict the movement, i.e. distance and direction to travel. The mean
         * of this belief should be set to the true value, so the prediction is
         * exact.
         */

        /*
         * Since we might be just transferring onto an edge, check
         * first.
         */
        final PathEdge initialEdge = startEdge.isEmptyEdge() ? sampledPathEdge
            : startEdge;
        currentEdge = initialEdge;

        if (newBelief.getInputDimensionality() == 4) {
          StandardRoadTrackingFilter.convertToRoadBelief(
              newBelief, InferredPath.getInferredPath(initialEdge));
        }

        double previousLocation = newBelief.getMean().getElement(0);
        movementFilter.predict(newBelief, initialEdge, initialEdge);

        final Vector transStateSample = StandardRoadTrackingFilter
            .sampleMovementBelief(
                rng, newBelief.getMean(), movementFilter);
        newBelief.setMean(transStateSample);
        totalDistToTravel = newBelief.getMean().getElement(0)
            - previousLocation;

        double newLocation = newBelief.getMean().getElement(0);
        final double L = initialEdge.getInferredEdge().getLength();
        
        /*
         * Adjust reference locations to be the same, wrt the new
         * location's direction.
         */
        if (newLocation < 0d && previousLocation > 0d) {
          previousLocation = -L + previousLocation;
          newLocation = previousLocation + totalDistToTravel;
          newBelief.getMean().setElement(0, newLocation);
        } else if (newLocation >= 0d && previousLocation < 0d){
          previousLocation = L + previousLocation;
          newLocation = previousLocation + totalDistToTravel;
          newBelief.getMean().setElement(0, newLocation);
        }

        /*
         * Get the distance we've covered to move off of this edge, if we
         * have moved off.
         */
        direction = totalDistToTravel >= 0d ? 1d : -1d;
        if (L < Math.abs(newLocation)) {
          final double r = L - Math.abs(previousLocation);
          distTraveled += r * direction;
        } else {
          distTraveled += totalDistToTravel;
        }
      } else {
        /*
         * Continue along edges
         */
        distTraveled += direction
            * sampledPathEdge.getInferredEdge().getLength();
        currentEdge = sampledPathEdge;
      }
      previousEdge = currentEdge;
      currentPath.add(currentEdge);
    }

    //    if(!Iterables.getLast(currentPath).isEmptyEdge() && 
    //          !Iterables.getLast(currentPath).isOnEdge(newBelief.getMean().getElement(0))) {
    //      Iterables.getLast(currentPath).isOnEdge(newBelief.getMean().getElement(0));
    //    }

    assert (Iterables.getLast(currentPath).isEmptyEdge() || Iterables
        .getLast(currentPath).isOnEdge(
            newBelief.getMean().getElement(0)));

    belief.setMean(newBelief.getMean());
    belief.setCovariance(newBelief.getCovariance());
    return InferredPath.getInferredPath(
        currentPath,
        (totalDistToTravel != null && totalDistToTravel < 0d) ? true
            : false);
  }

  @Override
  public VehicleState update(VehicleState previousParameter) {
    throw new NotImplementedException();
  }

  /**
   * The ParticleFilter.Updater interface isn't flexible enough.
   * 
   * @param previousParameter
   * @param obs
   * @return
   */
  public VehicleState update(VehicleState previousParameter,
    Observation obs) {
    final MultivariateGaussian currentLocBelief = previousParameter
        .getBelief().clone();
    final PathEdge currentPathEdge = PathEdge
        .getEdge(previousParameter.getInferredEdge());

    /*
     * Run through the edges, predict movement and reset the belief.
     */
    this.threadRandom.setSeed(this.threadRandom.get().nextLong());

    final EdgeTransitionDistributions sampledTransDist = previousParameter
        .getEdgeTransitionDist().clone();
    final Vector edgeMotionProbPriorSample = sampledTransDist.edgeMotionTransProbPrior
        .sample(this.threadRandom.get());

    final Vector freeMotionProbPriorSample = sampledTransDist.freeMotionTransProbPrior
        .sample(this.threadRandom.get());

    sampledTransDist.edgeMotionTransPrior
        .setParameters(edgeMotionProbPriorSample);
    sampledTransDist.freeMotionTransPrior
        .setParameters(freeMotionProbPriorSample);
    
    final StandardRoadTrackingFilter predictedFilter = 
        previousParameter.getMovementFilter().clone();

    final InferredPath newPath = traverseEdge(
        sampledTransDist, currentLocBelief, currentPathEdge,
        predictedFilter);

    final VehicleState newState = new VehicleState(
        this.inferredGraph, obs,
        previousParameter.getMovementFilter(), currentLocBelief,
        sampledTransDist, newPath, previousParameter);

    return newState;
  }

}