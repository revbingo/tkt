package com.revbingo.aws

class ReservationMatcher(val reservations: List<CountedReservation>) {

    fun match(instanceList: List<MatchedInstance>): List<MatchedInstance> {
        instanceList.filter { it.isRunning }.forEach { it.matchToReservation() }

        return instanceList
    }

    fun MatchedInstance.matchToReservation() {
        reservations.filter {
            it.computeUnits > 0 && it.isActive
        }.forEach { reservation ->
            if( this.matches(reservation) && reservation.computeUnits >= this.computeUnits) {
                    reservation.computeUnits -= this.computeUnits
                    this.matched = true
                    return
            }
        }
    }
}