//
///**
// * Default implementation of {@link ObservationRegistry}.
// *
// * @author Jonatan Ivanov
// * @author Tommy Ludwig
// * @author Marcin Grzejszczak
// * @since 1.10.0
// */
//class SimpleObservationRegistry implements ObservationRegistry {
//
//    private static final ThreadLocal<Observation.Scope> localObservationScope = new ThreadLocal<>();
//
//    private final ObservationConfig observationConfig = new ObservationConfig();
//
//    @Nullable
//    @Override
//    public Observation getCurrentObservation() {
//        Observation.Scope scope = localObservationScope.get();
//        if (scope != null) {
//            return scope.getCurrentObservation();
//        }
//        return null;
//    }
//
//    @Override
//    public Observation.Scope getCurrentObservationScope() {
//        return localObservationScope.get();
//    }
//
//    @Override
//    public void setCurrentObservationScope(Observation.Scope current) {
//        localObservationScope.set(current);
//    }
//
//    @Override
//    public ObservationConfig observationConfig() {
//        return this.observationConfig;
//    }
//
//    @Override
//    public boolean isNoop() {
//        return ObservationRegistry.super.isNoop() || observationConfig().getObservationHandlers().isEmpty();
//    }
//
//}
