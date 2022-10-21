from datetime import date, timedelta
from typing import Union

from ..conftest import verifier_for


# Constructor
def test_constructor():
    def function(year: int, month: int, day: int) -> date:
        return date(year, month, day)

    verifier = verifier_for(function)

    verifier.verify(2000, 1, 1, expected_result=date(2000, 1, 1))
    verifier.verify(2000, 1, 30, expected_result=date(2000, 1, 30))
    verifier.verify(2000, 2, 3, expected_result=date(2000, 2, 3))
    verifier.verify(2001, 1, 1, expected_result=date(2001, 1, 1))

    verifier.verify(2000, 1, 0, expected_error=ValueError)
    verifier.verify(2000, 1, 32, expected_error=ValueError)
    verifier.verify(2000, 0, 1, expected_error=ValueError)
    verifier.verify(2000, 13, 1, expected_error=ValueError)


# Instance attributes
def test_instance_attributes():
    def function(a: date) -> tuple:
        return a.year, a.month, a.day

    verifier = verifier_for(function)

    verifier.verify(date(2000, 1, 1), expected_result=(2000, 1, 1))
    verifier.verify(date(2000, 1, 30), expected_result=(2000, 1, 30))
    verifier.verify(date(2000, 2, 3), expected_result=(2000, 2, 3))
    verifier.verify(date(2001, 1, 1), expected_result=(2001, 1, 1))


# Operations
def test_comparisons():
    def less_than(a: date, b: date) -> bool:
        return a < b

    def greater_than(a: date, b: date) -> bool:
        return a > b

    def less_than_or_equal(a: date, b: date) -> bool:
        return a <= b

    def greater_than_or_equal(a: date, b: date) -> bool:
        return a >= b

    def equal(a: date, b: date) -> bool:
        return a == b

    def not_equal(a: date, b: date) -> bool:
        return a != b

    less_than_verifier = verifier_for(less_than)
    greater_than_verifier = verifier_for(greater_than)
    less_than_or_equal_verifier = verifier_for(less_than_or_equal)
    greater_than_or_equal_verifier = verifier_for(greater_than_or_equal)
    equal_verifier = verifier_for(equal)
    not_equal_verifier = verifier_for(not_equal)

    sorted_list_of_dates = [
        date(2000, 1, 3),
        date(2000, 1, 4),
        date(2000, 2, 2),
        date(2001, 1, 1)
    ]

    for first_index in range(len(sorted_list_of_dates)):
        for second_index in range(first_index, len(sorted_list_of_dates)):
            smaller = sorted_list_of_dates[first_index]
            bigger = sorted_list_of_dates[second_index]

            if first_index == second_index:
                less_than_verifier.verify(smaller, bigger, expected_result=False)
                greater_than_verifier.verify(smaller, bigger, expected_result=False)
                less_than_or_equal_verifier.verify(smaller, bigger, expected_result=True)
                greater_than_or_equal_verifier.verify(smaller, bigger, expected_result=True)
                equal_verifier.verify(smaller, bigger, expected_result=True)
                not_equal_verifier.verify(smaller, bigger, expected_result=False)
            else:
                less_than_verifier.verify(smaller, bigger, expected_result=True)
                greater_than_verifier.verify(smaller, bigger, expected_result=False)
                less_than_or_equal_verifier.verify(smaller, bigger, expected_result=True)
                greater_than_or_equal_verifier.verify(smaller, bigger, expected_result=False)
                equal_verifier.verify(smaller, bigger, expected_result=False)
                not_equal_verifier.verify(smaller, bigger, expected_result=True)

                less_than_verifier.verify(bigger, smaller, expected_result=False)
                greater_than_verifier.verify(bigger, smaller, expected_result=True)
                less_than_or_equal_verifier.verify(bigger, smaller, expected_result=False)
                greater_than_or_equal_verifier.verify(bigger, smaller, expected_result=True)
                equal_verifier.verify(bigger, smaller, expected_result=False)
                not_equal_verifier.verify(bigger, smaller, expected_result=True)


def test_subtract_date():
    def function(first_date: date, second_date: date) -> timedelta:
        return first_date - second_date

    verifier = verifier_for(function)

    verifier.verify(date(2000, 1, 1), date(2000, 1, 1), expected_result=timedelta(days=0))

    verifier.verify(date(2000, 1, 3), date(2000, 1, 1), expected_result=timedelta(days=2))
    verifier.verify(date(2000, 1, 1), date(2000, 1, 3), expected_result=timedelta(days=-2))

    verifier.verify(date(2000, 2, 1), date(2000, 1, 1), expected_result=timedelta(days=31))
    verifier.verify(date(2000, 1, 1), date(2000, 2, 1), expected_result=timedelta(days=-31))

    verifier.verify(date(2001, 1, 1), date(2000, 1, 1), expected_result=timedelta(days=366))
    verifier.verify(date(2000, 1, 1), date(2001, 1, 1), expected_result=timedelta(days=-366))


def test_add_timedelta():
    def function(first_date: date, difference: timedelta) -> date:
        return first_date + difference

    verifier = verifier_for(function)

    verifier.verify(date(2000, 1, 1), timedelta(days=0), expected_result=date(2000, 1, 1))

    verifier.verify(date(2000, 1, 1), timedelta(days=2), expected_result=date(2000, 1, 3))
    verifier.verify(date(2000, 1, 3), timedelta(days=-2), expected_result=date(2000, 1, 1))

    verifier.verify(date(2000, 1, 1), timedelta(days=31), expected_result=date(2000, 2, 1))
    verifier.verify(date(2000, 2, 1), timedelta(days=-31), expected_result=date(2000, 1, 1))

    verifier.verify(date(2000, 1, 1), timedelta(days=366), expected_result=date(2001, 1, 1))
    verifier.verify(date(2001, 1, 1), timedelta(days=-366), expected_result=date(2000, 1, 1))


def test_subtract_timedelta():
    def function(first_date: date, difference: timedelta) -> date:
        return first_date - difference

    verifier = verifier_for(function)

    verifier.verify(date(2000, 1, 1), timedelta(days=0), expected_result=date(2000, 1, 1))

    verifier.verify(date(2000, 1, 3), timedelta(days=2), expected_result=date(2000, 1, 1))
    verifier.verify(date(2000, 1, 1), timedelta(days=-2), expected_result=date(2000, 1, 3))

    verifier.verify(date(2000, 2, 1), timedelta(days=31), expected_result=date(2000, 1, 1))
    verifier.verify(date(2000, 1, 1), timedelta(days=-31), expected_result=date(2000, 2, 1))

    verifier.verify(date(2001, 1, 1), timedelta(days=366), expected_result=date(2000, 1, 1))
    verifier.verify(date(2000, 1, 1), timedelta(days=-366), expected_result=date(2001, 1, 1))


def test_fromtimestamp():
    def function(timestamp: Union[int, float]) -> date:
        return date.fromtimestamp(timestamp)

    verifier = verifier_for(function)

    verifier.verify(0, expected_result=date(1969, 12, 31))
    verifier.verify(4000, expected_result=date(1969, 12, 31))
    verifier.verify(200000, expected_result=date(1970, 1, 3))


def test_fromordinal():
    def function(ordinal: int) -> date:
        return date.fromordinal(ordinal)

    verifier = verifier_for(function)

    verifier.verify(1, expected_result=date(1, 1, 1))
    verifier.verify(2, expected_result=date(1, 1, 2))
    verifier.verify(32, expected_result=date(1, 2, 1))
    verifier.verify(1000, expected_result=date(3, 9, 27))


def test_fromisoformat():
    def function(date_string: str) -> date:
        return date.fromisoformat(date_string)

    verifier = verifier_for(function)

    verifier.verify('2000-01-01', expected_result=date(2000, 1, 1))
    verifier.verify('1999-02-03', expected_result=date(1999, 2, 3))


def test_fromisocalendar():
    def function(year: int, week: int, day: int) -> date:
        return date.fromisocalendar(year, week, day)

    verifier = verifier_for(function)

    verifier.verify(2000, 1, 1, expected_result=date(2000, 1, 3))
    verifier.verify(1999, 2, 3, expected_result=date(1999, 1, 13))


# TODO: replace


def test_timetuple():
    def function(x: date) -> tuple:
        return x.timetuple()

    verifier = verifier_for(function)

    # TODO: enable type checking when named tuples are supported
    verifier.verify(date(1, 1, 1), expected_result=(1, 1, 1, 0, 0, 0, 0, 1, -1), type_check=False)
    verifier.verify(date(3, 9, 27), expected_result=(3, 9, 27, 0, 0, 0, 5, 270, -1), type_check=False)


def test_toordinal():
    def function(x: date) -> int:
        return x.toordinal()

    verifier = verifier_for(function)

    verifier.verify(date(1, 1, 1), expected_result=1)
    verifier.verify(date(1, 1, 2), expected_result=2)
    verifier.verify(date(1, 2, 1), expected_result=32)
    verifier.verify(date(3, 9, 27), expected_result=1000)


def test_weekday():
    def function(x: date) -> int:
        return x.weekday()

    verifier = verifier_for(function)

    verifier.verify(date(2002, 12, 4), expected_result=2)


def test_isoweekday():
    def function(x: date) -> int:
        return x.isoweekday()

    verifier = verifier_for(function)

    verifier.verify(date(2002, 12, 4), expected_result=3)


def test_isocalendar():
    def function(x: date) -> tuple:
        return x.isocalendar()

    verifier = verifier_for(function)

    verifier.verify(date(2003, 12, 29), expected_result=(2004, 1, 1), type_check=False)
    verifier.verify(date(2004, 1, 4), expected_result=(2004, 1, 7), type_check=False)


def test_isoformat():
    def function(x: date) -> str:
        return x.isoformat()

    verifier = verifier_for(function)

    verifier.verify(date(2003, 12, 29), expected_result='2003-12-29')
    verifier.verify(date(2004, 1, 4), expected_result='2004-01-04')


def test_str():
    def function(x: date) -> str:
        return str(x)

    verifier = verifier_for(function)

    verifier.verify(date(2003, 12, 29), expected_result='2003-12-29')
    verifier.verify(date(2004, 1, 4), expected_result='2004-01-04')


def test_ctime():
    def function(x: date) -> str:
        return x.ctime()

    verifier = verifier_for(function)

    verifier.verify(date(2002, 12, 4), expected_result='Wed Dec  4 00:00:00 2002')
