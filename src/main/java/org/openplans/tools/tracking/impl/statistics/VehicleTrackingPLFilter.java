package org.openplans.tools.tracking.impl.statistics;

import gov.sandia.cognition.collection.ScalarMap.Entry;
import gov.sandia.cognition.math.LogMath;
import gov.sandia.cognition.math.MutableDouble;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.AbstractParticleFilter;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.util.DefaultPair;
import gov.sandia.cognition.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.openplans.tools.tracking.impl.LogDefaultDataDistribution;
import org.openplans.tools.tracking.impl.MutableDoubleCount;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.VehicleState;
import org.openplans.tools.tracking.impl.VehicleState.VehicleStateInitialParameters;
import org.openplans.tools.tracking.impl.VehicleTrackingFilter;
import org.openplans.tools.tracking.impl.graph.InferredEdge;
import org.openplans.tools.tracking.impl.graph.paths.InferredPath;
import org.openplans.tools.tracking.impl.graph.paths.InferredPath.EdgePredictiveResults;
import org.openplans.tools.tracking.impl.graph.paths.InferredPathEntry;
import org.openplans.tools.tracking.impl.graph.paths.PathEdge;
import org.openplans.tools.tracking.impl.util.OtpGraph;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

public class VehicleTrackingPLFilter extends
    AbstractParticleFilter<Observation, VehicleState> implements
    VehicleTrackingFilter<Observation, VehicleState> {

  private static final long serialVersionUID = -8257075186193062150L;

  /*
   * Populate this when you want a generalized graph. TODO Otherwise, one is
   * created for each particle.
   */
  private final OtpGraph inferredGraph;

  private double prevTime = 0;

  private DataDistribution<VehicleState> previousResampleDist;

  private final Map<Observation, FilterInformation> filterInfo = Maps
      .newHashMap();

  private final boolean isDebug;

  private final Observation initialObservation;

  public VehicleTrackingPLFilter(Observation obs,
    OtpGraph inferredGraph, VehicleStateInitialParameters parameters,
    boolean isDebug) {
    this.isDebug = isDebug;
    this.setNumParticles(50);
    this.inferredGraph = inferredGraph;
    this.setUpdater(new VehicleTrackingPathSamplerFilterUpdater(
        obs, this.inferredGraph, parameters));
    this.initialObservation = obs;
  }

  @Override
  public DataDistribution<VehicleState> createInitialLearnedObject() {
    final DataDistribution<VehicleState> dist = super
        .createInitialLearnedObject();
    if (isDebug) {
      final Set<InferredPath> evaledPaths = Sets.newHashSet();
      for (final VehicleState state : dist.getDomain()) {
        // TODO FIXME provide real info here
        evaledPaths.add(state.getPath());
      }
      this.filterInfo.put(initialObservation, new FilterInformation(
          evaledPaths, dist));
    }
    return dist;
  }

  @Override
  public FilterInformation getFilterInformation(Observation obs) {
    return this.filterInfo.get(obs);
  }

  @Override
  public Random getRandom() {
    final VehicleTrackingPathSamplerFilterUpdater updater = (VehicleTrackingPathSamplerFilterUpdater) this
        .getUpdater();
    return updater.getThreadRandom().get();
  }

  /**
   * Note: this skips observations with a time delta of zero or less.
   */
  @Override
  public void update(DataDistribution<VehicleState> target,
    Observation obs) {

    final double timeDiff = prevTime == 0 ? 1d : (obs.getTimestamp()
        .getTime() - prevTime) / 1000;

    if (timeDiff <= 0)
      return;

    final Multimap<VehicleState, WrappedWeightedValue<InferredPathEntry>> stateToPaths = HashMultimap
        .create();
    final Set<InferredPath> evaluatedPaths = Sets.newHashSet();

    /*
     * Resample based on predictive likelihood to get a smoothed sample
     */
    final List<WrappedWeightedValue<VehicleState>> resampler = Lists
        .newArrayList();
    for (final VehicleState state : target.getDomain()) {

      final int count = ((LogDefaultDataDistribution)target).getCount(state);
      
      final Set<InferredPath> instStateTransitions = inferredGraph
          .getPaths(state, obs.getObsPoint());

      state.getMovementFilter().setCurrentTimeDiff(timeDiff);
      double totalLogLik = Double.NEGATIVE_INFINITY;

      /*
       * Create one table to hold all pathEdges to their
       * likelihoods.  That way we can check for dups from
       * overlapping paths.
       * TODO determine if sharing this map between states is useful.
       */
      final Map<Pair<PathEdge, Boolean>, EdgePredictiveResults> edgeToPreBeliefAndLogLik = Maps
          .newHashMap();

      for (final InferredPath path : instStateTransitions) {

        /*
         * Make sure that this path is valid for the state.
         */
        if (!state.getInferredEdge().isEmptyEdge()
            && !path.isEmptyPath()
            && !state.getInferredEdge().equals(
                Iterables.getFirst(path.getEdges(), null)
                    .getInferredEdge()))
          continue;

        final InferredPathEntry infPath = path
            .getPredictiveLogLikelihood(
                obs, state, edgeToPreBeliefAndLogLik);

        if (isDebug)
          evaluatedPaths.add(path);

        if (infPath != null) {
          totalLogLik = LogMath.add(
              totalLogLik, infPath.getTotalLogLikelihood());

          assert !Double.isNaN(totalLogLik);

          stateToPaths.put(
              state, new WrappedWeightedValue<InferredPathEntry>(
                  infPath, infPath.getTotalLogLikelihood()));
        }
      }

      resampler.add(new WrappedWeightedValue<VehicleState>(
          state, totalLogLik, count));
    }

    final Random rng = getRandom();

    final DataDistribution<VehicleState> resampleDist = StatisticsUtil
        .getLogNormalizedDistribution(resampler);

    // TODO low-variance sampling?
    final ArrayList<? extends VehicleState> smoothedStates = resampleDist
        .sample(rng, getNumParticles());

    if (isDebug)
      this.filterInfo.put(obs, new FilterInformation(
          evaluatedPaths, resampleDist));

    final DataDistribution<VehicleState> posteriorDist = new LogDefaultDataDistribution<VehicleState>();
    /*
     * Propagate states
     */
    for (final VehicleState state : smoothedStates) {

//      final int count = ((LogDefaultDataDistribution)resampleDist).getCount(state);
      final VehicleState newState = state.clone();
      final DataDistribution<InferredPathEntry> instStateDist = StatisticsUtil
          .getLogNormalizedDistribution(Lists
              .newArrayList(stateToPaths.get(newState)));
      final InferredPathEntry sampledPathEntry = instStateDist
          .sample(rng);

      /*-
       * Now, if you need to, propagate/sample a predictive location state. 
       * TODO don't need to now, but will when estimating state covariance/precision
       * parameters
       */

      /*
       * State suffient stats are next (e.g. kalman params)
       */

      /*
       * This is a bit confusing, so really try to understand this:
       * The edge we're about to sample is not necessarily the edge that our filtering
       * says we should be on.  The edges, in this case, only correspond to stretches of
       * length-locations that were evaluated.  The posterior/filtering result that we
       * obtain from these edges will adjust our previous length-location relative to how good
       * it would've/could've been to be on each edge.  Essentially, this is kind of like saying
       * that we have to walk to that better edge relative to how fast we are, not simply teleport.
       */
      final Pair<PathEdge, Boolean> directionalSampledEdge;
      if (sampledPathEntry.getPath().getEdges().size() > 1) {
        final DataDistribution<PathEdge> pathEdgeDist = StatisticsUtil
            .getLogNormalizedDistribution(sampledPathEntry
                .getWeightedPathEdges());
        directionalSampledEdge = new DefaultPair<PathEdge, Boolean>(
            pathEdgeDist.sample(rng), sampledPathEntry.getPath()
                .isBackward());
      } else {
        directionalSampledEdge = new DefaultPair<PathEdge, Boolean>(
            sampledPathEntry.getPath().getEdges().get(0),
            sampledPathEntry.getPath().isBackward());
      }
      final MultivariateGaussian sampledBelief = sampledPathEntry
          .getEdgeToPredictiveBelief().get(directionalSampledEdge)
          .getWeightedPredictiveDist().getValue().clone();

      /*-
       * Propagate sufficient stats (can be done off-line) Just the edge
       * transitions for now.
       */
      final StandardRoadTrackingFilter updatedFilter = sampledPathEntry
          .getFilter().clone();

      final PathEdge actualPosteriorEdge;
      if (!sampledPathEntry.getPath().isEmptyPath()) {
        //        final PathEdge actualPriorEdge = sampledPathEntry.getPath().getEdgeForDistance(
        //            sampledBelief.getMean().getElement(0));
        updatedFilter.measure(
            sampledBelief, obs.getProjectedPoint(),
            sampledPathEntry.getPath());

        actualPosteriorEdge = sampledPathEntry.getPath()
            .getEdgeForDistance(
                sampledBelief.getMean().getElement(0), true);
      } else {
        updatedFilter.measure(
            sampledBelief, obs.getProjectedPoint(),
            sampledPathEntry.getPath());
        actualPosteriorEdge = directionalSampledEdge.getFirst();
      }

      InferredEdge prevEdge = sampledPathEntry.getPath().getEdges()
          .get(0).getInferredEdge();
      final EdgeTransitionDistributions updatedEdgeTransDist = newState
          .getEdgeTransitionDist().clone();
      for (final PathEdge edge : sampledPathEntry.getPath()
          .getEdges()) {
        if (prevEdge != null)
          updatedEdgeTransDist.update(
              prevEdge, edge.getInferredEdge());

        if (!edge.isEmptyEdge()) {
          edge
              .getInferredEdge()
              .getVelocityEstimator()
              .update(
                  edge.getInferredEdge().getVelocityPrecisionDist(),
                  Math.abs(sampledBelief.getMean().getElement(1)));
        }

        if (edge.equals(actualPosteriorEdge))
          break;
        prevEdge = edge.getInferredEdge();
      }

      final VehicleState newTransState = new VehicleState(
          this.inferredGraph, obs, updatedFilter, sampledBelief,
          updatedEdgeTransDist, sampledPathEntry.getPath(), state);

      ((LogDefaultDataDistribution)posteriorDist).increment(newTransState, 1d / numParticles);

    }

    target.clear();
    ((LogDefaultDataDistribution<VehicleState>)target).copyAll(posteriorDist);
    
    assert ((LogDefaultDataDistribution<VehicleState>)target).getTotalCount() == this.numParticles;

    prevTime = obs.getTimestamp().getTime();
  }

}
