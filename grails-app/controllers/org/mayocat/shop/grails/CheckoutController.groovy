package org.mayocat.shop.grails

class CheckoutController extends AbstractExposedController {

  /**
   * on GET
   */
  def expose() {
    render(view: "index.html", model:[template:"checkout"])
  }

  /**
   * on POST form submit -> try to do an actual checkout
   */
  def exposeDoCheckout() {
    def order = new Order(params)

    if (order.validate()) {
      render(view: "index.html", model:[template:"checkout"])
    }
    else {
      render(view: "index.html", model:[template:"checkout", order: order, errors: order.errors.allErrors])
    }
  }
  
  def afterInterceptor = [action:super.afterExpose, only: ['expose', 'exposeDoCheckout']]
}