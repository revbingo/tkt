package com.revbingo.aws

class ReservationMatcher {

    fun match(riList: List<CountedReservation>, instanceList: List<MatchedInstance>): List<MatchedInstance> {
        instanceList.filter { it.isRunning }.forEach { matchToReservation(it, riList) }

        return instanceList
    }

    fun matchToReservation(instance: MatchedInstance, reservations: List<CountedReservation>): Unit {
        reservations.filter {
            it.unmatchedCount > 0 && it.isActive
        }.forEach { reservation ->
            if( instance.matches(reservation)) {
                    reservation.unmatchedCount--
                    instance.matched = true
                    return
            }
        }
    }
}