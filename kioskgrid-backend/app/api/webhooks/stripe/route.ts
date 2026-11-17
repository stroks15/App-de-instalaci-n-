import { NextRequest, NextResponse } from 'next/server';
import { handleWebhookEvent } from '@/lib/stripe';
import { getSupabase } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const body = await request.text();
    const signature = request.headers.get('stripe-signature');

    if (!signature) {
      return NextResponse.json({ error: 'Missing stripe-signature header' }, { status: 400 });
    }

    const supabase = await getSupabase();
    const event = await handleWebhookEvent(body, signature);

    // Handle specific event types that need database updates
    switch (event.type) {
      case 'checkout.session.completed': {
        const session = event.data.object as any;

        // Check if this was a permanent access purchase (DEXTERH4CK coupon)
        const isPermanent = session.metadata?.permanent_access === 'true' ||
          session.discounts?.some((d: any) => d.coupon?.name === 'DEXTERH4CK');

        if (isPermanent && session.customer_email) {
          // Update user in Supabase to mark as permanent paid user
          const { error } = await (supabase.from('users') as any)
            .upsert({
              email: session.customer_email,
              is_permanent_paid: true,
              permanent_paid_at: new Date().toISOString(),
              stripe_customer_id: session.customer,
              updated_at: new Date().toISOString(),
            }, { onConflict: 'email' });

          if (error) {
            console.error('Error updating user for permanent access:', error);
          }
        }
        break;
      }
      case 'customer.subscription.deleted': {
        // Handle subscription cancellation
        const subscription = event.data.object as any;
        console.log('Subscription cancelled:', subscription.id);
        break;
      }
    }

    return NextResponse.json({ received: true });
  } catch (error) {
    console.error('Webhook error:', error);
    return NextResponse.json({ error: 'Webhook handler failed' }, { status: 400 });
  }
}