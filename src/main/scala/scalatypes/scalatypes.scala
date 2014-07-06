package scalatypes

import scala.language.higherKinds
import scala.language.implicitConversions

/**
 *  A Semigroup defines an associative binary function.
 *
 *  {{{
 *  Semigroup[Int].mappend(3,4)
 *  }}}
 *
 *  @tparam A the type of the semigroup
 *  @see [[http://en.wikipedia.org/wiki/Semigroup]]
 */
trait Semigroup[A] {
  def mappend(a: A, b: A): A
}

/**
 * A Monoid is a special [[Semigroup]] together with an identity element call mzero.
 *
 * @tparam A the type of the monoid.
 * @see [[http://en.wikipedia.org/wiki/Monoid]]
 */
trait Monoid[A] extends Semigroup[A] {
  def mzero: A
}

object Monoid {
  def apply[A](implicit F: Monoid[A]): Monoid[A] = F
}

/**
 * A functor is a structure which defines a mapping from the type A to type B.
 *
 * @tparam F a type constructor.
 * @see [[http://en.wikipedia.org/wiki/Functor]]
 */
trait Functor[F[_]] {
  /**
   * the mapping function.
   * {{{
   * 	val listString = Functor[List].map(listInt){ (a:Int) => a.toString }
   */
  def map[A, B](F: F[A])(f: A => B): F[B]
}

object Functor {
  def apply[F[_]](implicit F: Functor[F]) = F
}

trait Applicative[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]

  def ap[A, B](F: => F[A])(f: => F[A => B]): F[B]

  // derived functions    

  def ap2[A, B, C](fa: => F[A], fb: => F[B])(f: F[(A, B) => C]): F[C] =
    ap(fb)(ap(fa)(map(f)(_.curried)))

  override def map[A, B](fa: F[A])(f: A => B): F[B] =
    ap(fa)(pure(f))

  def apply2[A, B, C](fa: => F[A], fb: => F[B])(f: (A, B) => C): F[C] =
    ap2(fa, fb)(pure(f))
}

/**
 * A Monad specialises a [[Functor]].
 *
 * @tparam F[_] a type constructor
 * @see [[http://ncatlab.org/nlab/show/monad+%28in+computer+science%29]]
 */
trait Monad[F[_]] extends Applicative[F] {

  def flatMap[A, B](F: F[A])(f: A => F[B]): F[B]

  /**
   *  Implementation of `map` in terms of `flatMap`
   *
   */
  override def map[A, B](F: F[A])(f: A => B): F[B] = {
    flatMap(F)(a => pure(f(a)))
  }
  
  def ap[A, B](fa: => F[A])(f: => F[A => B]): F[B] = {
    lazy val fa0 : F[A] = fa
    // map(fa0) is a partially applied function
    // val  m : (A => B) => F[B] = map(fa0) _
    flatMap(f)(map(fa0))
  }
}

object Monad {
  def apply[F[_]](implicit F: Monad[F]): Monad[F] = F
}

/**
 * A natural transformation defines a kind of conversion between type constructors.
 *
 * @tparam F
 * @tparam G
 * @see [[http://en.wikipedia.org/wiki/Natural_transformation]]
 */
trait NaturalTransformation[-F[_], +G[_]] {
  def apply[A](F: F[A]): G[A]
}

object NaturalTransformation {
  def apply[F[_], G[_]](implicit NT: NaturalTransformation[F, G]): NaturalTransformation[F, G] = NT
  /**
   *  defines an implicit conversion from a natural transformation to a function `F[A] => G[A]`
   */
  implicit def reify[F[_], G[_], A](NT: F ~> G): F[A] => G[A] = { f => NT(f) }
}

sealed trait Free[F[_], A] {
  def flatMap[B](f: A => Free[F, B]): Free[F, B] =
    this match {
      case Return(a) => f(a)
      case Bind(fx, g) =>
        Bind(fx, g andThen (_ flatMap f))
    }

  def map[B](f: A => B): Free[F, B] =
    flatMap(a => Return(f(a)))

  def foldMap[G[_]: Monad](f: F ~> G): G[A] =
    this match {
      case Return(a) => Monad[G].pure(a)
      case Bind(fx, g) =>
        Monad[G].flatMap(f(fx)) { a =>
          g(a).foldMap(f)
        }
    }
}

case class Return[F[_], A](a: A)
  extends Free[F, A]

case class Bind[F[_], I, A](
  a: F[I],
  f: I => Free[F, A]) extends Free[F, A]

/**
 *
 * @see [[
 * @see [[http://ncatlab.org/nlab/show/Kleisli+category]]
 */
trait Kleisli[F[_], A, B] {
  import Kleisli._
  def run(a: A): F[B]

  def andThen[C](k: Kleisli[F, B, C])(implicit b: Monad[F]): Kleisli[F, A, C] =
    kleisli((a: A) => b.flatMap(this.run(a))(k.run _))

  def >=>[C](k: Kleisli[F, B, C])(implicit b: Monad[F]): Kleisli[F, A, C] = this andThen k

  def >==>[C](f: B => F[C])(implicit b: Monad[F]) = this andThen kleisli(f)

  def compose[C](k: Kleisli[F, C, A])(implicit b: Monad[F]): Kleisli[F, C, B] = {
    k >=> this
  }

  def <=<[C](k: Kleisli[F, C, A])(implicit b: Monad[F]): Kleisli[F, C, B] = this compose k

  def <==<[C](f: C => F[A])(implicit b: Monad[F]): Kleisli[F, C, B] = this compose kleisli(f)

  def map[C](f: B => C)(implicit G: Functor[F]): Kleisli[F, A, C] = kleisli {
    (a: A) =>
      val b = this.run(a)
      G.map(b)(f)
  }

  def mapK[G[_], C](f: F[B] => G[C])(implicit F: Functor[F]): Kleisli[G, A, C] = kleisli {
    a: A =>
      f(this.run(a))
  }

  def flatMap[C](f: B => Kleisli[F, A, C])(implicit G: Monad[F]): Kleisli[F, A, C] = kleisli {
    (r: A) =>
      val b = this.run(r)
      G.flatMap(b) { b: B => f(b).run(r) }
  }

  def flatMapK[C](f: B => F[C])(implicit F: Monad[F]): Kleisli[F, A, C] =
    kleisli(a => F.flatMap(run(a))(f))

}

object Kleisli {
  def kleisli[F[_], A, B](f: A => F[B]): Kleisli[F, A, B] = new Kleisli[F, A, B] {
    def run(a: A): F[B] = f(a)
  }
}

trait State[S, A] {
  def run(s: S): (S, A)
  def map[B](f: A => B): State[S, B] = State[S, B] {
    s =>
      val (s1, a) = run(s)
      (s1, f(a))
  }
  def flatMap[B](f: A => State[S, B]): State[S, B] = State[S, B] {
    s =>
      val (s1, a) = run(s)
      val b = f(a)
      b.run(s1)
  }
}

object State {
  def apply[S, A](f: S => (S, A)) = new State[S, A] {
    def run(s: S) = f(s)
  }
}
